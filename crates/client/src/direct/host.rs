//! [`DirectHost`] — the Direct-mode backend, modelled on `DemoHost`: the
//! engine's IPC watch streams are mirrored into the local registry replica
//! (settled in-process, never pushed anywhere) and into per-chat shadow
//! session docs; commands the client writes into a doc are forwarded with
//! `RelayCommand` (keeping the phone-minted ids) and marked applied locally.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;

use tokio_util::sync::CancellationToken;
use zeron_doc::transcript_delta::apply_transcript_frame;
use zeron_doc::{
    RegistryDoc, SegmentWriter, SessionCommandStatus, SessionDoc, SessionMessageEntry,
    TranscriptUpdate,
};
use zeron_proto::{Chat, Device, Session, Space};

use super::ssh::{self, SshSession};
use super::{SshError, SshTarget};
use crate::client::ClientInner;
use crate::demo::DemoServer;
use crate::error::{ClientError, Result};
use crate::session::SessionCore;
use crate::{lock, now_ms};

const BACKOFF_MIN: Duration = Duration::from_secs(1);
const BACKOFF_MAX: Duration = Duration::from_secs(30);
/// Presence beat while the link is up (well inside PRESENCE_FRESH_MS).
const BEAT: Duration = Duration::from_secs(5);
/// Host RPC budget (generous: mobile networks, big replies).
const CALL_TIMEOUT: Duration = Duration::from_secs(45);
/// How long a call waits for a (re)connecting link.
const LINK_WAIT: Duration = Duration::from_secs(20);
/// Phone-created rows survive a mirror pass this long before the engine's
/// echo must include them.
const LOCAL_ROW_GRACE_MS: i64 = 20_000;
const REGISTRY_FILE: &str = "direct-registry.bin";

struct Link {
    ssh: SshSession,
    rpc: zeron_rpc::RpcClient,
    cancel: CancellationToken,
    engine_device_id: String,
}

#[derive(Debug, Clone, Default)]
pub(crate) struct LinkStatus {
    pub(crate) connected: bool,
    pub(crate) retry_at_ms: Option<i64>,
    pub(crate) last_error: Option<String>,
}

pub(crate) struct DirectHost {
    target: SshTarget,
    client: Weak<ClientInner>,
    server: Mutex<DemoServer>,
    link: Mutex<Option<Arc<Link>>>,
    link_changed: tokio::sync::watch::Sender<u64>,
    status: Mutex<LinkStatus>,
    engine_device: Mutex<Option<String>>,
    /// Transcript mirrors: chat → (core it feeds, stop token).
    mirrors: Mutex<HashMap<String, (Weak<SessionCore>, CancellationToken)>>,
    drains: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
    local_rows: Mutex<HashMap<String, i64>>,
    wake: tokio::sync::Notify,
    cancel: Mutex<Option<CancellationToken>>,
}

fn doc_err(err: zeron_doc::DocError) -> ClientError {
    ClientError::Internal(err.to_string())
}

impl DirectHost {
    pub(crate) fn new(client: &Arc<ClientInner>, target: SshTarget) -> Arc<Self> {
        Arc::new(Self {
            target,
            client: Arc::downgrade(client),
            server: Mutex::new(DemoServer::default()),
            link: Mutex::new(None),
            link_changed: tokio::sync::watch::channel(0).0,
            status: Mutex::new(LinkStatus::default()),
            engine_device: Mutex::new(None),
            mirrors: Mutex::new(HashMap::new()),
            drains: Mutex::new(HashMap::new()),
            local_rows: Mutex::new(HashMap::new()),
            wake: tokio::sync::Notify::new(),
            cancel: Mutex::new(None),
        })
    }

    /// Cached replica from the last session (instant first paint; also
    /// carries the phone-only sidebar sections).
    pub(crate) fn load_registry(data_dir: &std::path::Path, device_id: &str) -> RegistryDoc {
        std::fs::read(data_dir.join(REGISTRY_FILE))
            .ok()
            .and_then(|bytes| RegistryDoc::from_bytes(&bytes, device_id).ok())
            .unwrap_or_else(|| RegistryDoc::new(device_id.to_owned()))
    }

    pub(crate) fn save_registry(&self) {
        let Some(client) = self.client.upgrade() else {
            return;
        };
        let bytes = client.workspace.mutate(|doc| doc.to_bytes());
        match bytes {
            Ok(bytes) => {
                let dir = &client.config.data_dir;
                let _ = std::fs::create_dir_all(dir);
                let tmp = dir.join(format!("{REGISTRY_FILE}.tmp"));
                if std::fs::write(&tmp, bytes).is_ok() {
                    let _ = std::fs::rename(&tmp, dir.join(REGISTRY_FILE));
                }
            }
            Err(err) => tracing::debug!(error = %err, "direct registry snapshot failed"),
        }
    }

    pub(crate) fn status(&self) -> LinkStatus {
        lock(&self.status).clone()
    }

    /// Apply + ack local registry writes in-process (phone-only state such
    /// as sidebar sections lives here; engine rows arrive via the mirror).
    pub(crate) fn settle_registry(&self, client: &ClientInner) {
        let mut server = lock(&self.server);
        client.workspace.mutate(|doc| server.settle(doc));
    }

    pub(crate) fn note_local_row(&self, id: &str) {
        lock(&self.local_rows).insert(id.to_owned(), now_ms());
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    pub(crate) fn start(self: &Arc<Self>, cancel: CancellationToken) {
        *lock(&self.cancel) = Some(cancel.clone());
        if let Some(client) = self.client.upgrade() {
            // Rows cached from the last session render at once.
            self.settle_registry(&client);
        }
        let host = self.clone();
        crate::runtime::shared().spawn(async move { host.supervise(cancel).await });
    }

    pub(crate) fn stop(&self) {
        self.save_registry();
        if let Some(link) = lock(&self.link).take() {
            link.cancel.cancel();
        }
        for (_, (_, token)) in lock(&self.mirrors).drain() {
            token.cancel();
        }
    }

    /// Reconnect now (foreground, network back, user retry).
    pub(crate) fn kick(&self) {
        self.wake.notify_one();
    }

    fn set_status(&self, f: impl FnOnce(&mut LinkStatus)) {
        f(&mut lock(&self.status));
        if let Some(client) = self.client.upgrade() {
            client.recompute_connectivity();
        }
    }

    async fn supervise(self: Arc<Self>, cancel: CancellationToken) {
        let mut backoff = BACKOFF_MIN;
        loop {
            if cancel.is_cancelled() {
                return;
            }
            let attempt = tokio::select! {
                _ = cancel.cancelled() => return,
                r = self.open_link() => r,
            };
            let wait = match attempt {
                Ok(link) => {
                    backoff = BACKOFF_MIN;
                    self.run_link(link, &cancel).await;
                    if cancel.is_cancelled() {
                        return;
                    }
                    Some(BACKOFF_MIN)
                }
                Err(err) => {
                    tracing::info!(error = %err, "direct link failed");
                    let wait = (!err.needs_user()).then_some(backoff);
                    backoff = (backoff * 2).min(BACKOFF_MAX);
                    let message = err.to_string();
                    self.set_status(|s| {
                        s.connected = false;
                        s.last_error = Some(message);
                        s.retry_at_ms = wait.map(|w| now_ms() + w.as_millis() as i64);
                    });
                    wait
                }
            };
            // Needs-user failures park until kicked (e.g. the app restarts
            // the client with a trusted key).
            tokio::select! {
                _ = cancel.cancelled() => return,
                _ = self.wake.notified() => {}
                _ = async {
                    match wait {
                        Some(w) => tokio::time::sleep(w).await,
                        None => std::future::pending().await,
                    }
                } => {}
            }
        }
    }

    async fn open_link(&self) -> std::result::Result<Link, SshError> {
        let ssh = ssh::connect(&self.target).await?;
        let rpc = ssh.open_engine(self.target.engine_port).await?;
        let info = tokio::time::timeout(
            Duration::from_secs(20),
            rpc.call(zeron_rpc::methods::ENGINE_INFO, serde_json::json!({})),
        )
        .await
        .map_err(|_| SshError::Engine("EngineInfo timed out".into()))?
        .map_err(|e| SshError::Engine(e.to_string()))?;
        let engine_device_id = info
            .get("deviceId")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_owned();
        Ok(Link {
            ssh,
            rpc,
            cancel: CancellationToken::new(),
            engine_device_id,
        })
    }

    async fn run_link(self: &Arc<Self>, link: Link, cancel: &CancellationToken) {
        let link = Arc::new(link);
        let Some(client) = self.client.upgrade() else {
            return;
        };
        *lock(&self.engine_device) = Some(link.engine_device_id.clone());
        client
            .workspace
            .set_presence(&link.engine_device_id, now_ms());
        *lock(&self.link) = Some(link.clone());
        self.link_changed.send_modify(|g| *g += 1);
        self.set_status(|s| {
            s.connected = true;
            s.retry_at_ms = None;
            s.last_error = None;
        });
        drop(client);

        for method in [
            zeron_rpc::methods::WATCH_DEVICES,
            zeron_rpc::methods::WATCH_SPACES,
            zeron_rpc::methods::WATCH_CHATS,
            zeron_rpc::methods::WATCH_SESSIONS,
        ] {
            let host = self.clone();
            let link = link.clone();
            crate::runtime::shared().spawn(async move {
                host.watch_registry(&link, method).await;
                // A registry stream ending means the tunnel is gone.
                link.cancel.cancel();
            });
        }
        // Re-attach every open session and flush commands queued offline.
        if let Some(client) = self.client.upgrade() {
            for core in client.cores() {
                self.restart_mirror(&core, &link);
                self.on_command(&core.chat_id);
            }
        }

        let mut beat = tokio::time::interval(BEAT);
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                _ = link.cancel.cancelled() => break,
                _ = beat.tick() => {
                    if link.ssh.is_closed() {
                        break;
                    }
                    if let Some(client) = self.client.upgrade() {
                        client.workspace.set_presence(&link.engine_device_id, now_ms());
                    }
                }
            }
        }
        link.cancel.cancel();
        {
            let mut slot = lock(&self.link);
            if slot.as_ref().is_some_and(|l| Arc::ptr_eq(l, &link)) {
                *slot = None;
            }
        }
        self.link_changed.send_modify(|g| *g += 1);
        link.ssh.close().await;
        self.save_registry();
        if let Some(client) = self.client.upgrade() {
            // Presence decays on its own; drop it now so the dot flips.
            client.workspace.set_presence(&link.engine_device_id, 0);
            client.recompute_workspace();
        }
        self.set_status(|s| {
            s.connected = false;
            s.last_error = Some("connection to the machine lost".into());
            s.retry_at_ms = Some(now_ms() + BACKOFF_MIN.as_millis() as i64);
        });
    }

    fn current_link(&self) -> Option<Arc<Link>> {
        lock(&self.link)
            .clone()
            .filter(|l| !l.cancel.is_cancelled())
    }

    async fn wait_link(&self, budget: Duration) -> Result<Arc<Link>> {
        let mut rx = self.link_changed.subscribe();
        let deadline = tokio::time::Instant::now() + budget;
        loop {
            if let Some(link) = self.current_link() {
                return Ok(link);
            }
            self.kick();
            match tokio::time::timeout_at(deadline, rx.changed()).await {
                Ok(Ok(())) => continue,
                _ => {
                    let why = self
                        .status()
                        .last_error
                        .unwrap_or_else(|| "not connected".into());
                    return Err(ClientError::HostUnavailable(why));
                }
            }
        }
    }

    /// One engine RPC over the tunnel.
    pub(crate) async fn call(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<serde_json::Value> {
        let link = self.wait_link(LINK_WAIT).await?;
        match tokio::time::timeout(CALL_TIMEOUT, link.rpc.call(method, params)).await {
            Err(_) => Err(ClientError::HostUnavailable(format!("{method} timed out"))),
            Ok(Err(zeron_rpc::RpcError::Closed)) | Ok(Err(zeron_rpc::RpcError::Transport(_))) => {
                link.cancel.cancel();
                Err(ClientError::HostUnavailable("connection lost".into()))
            }
            Ok(Err(zeron_rpc::RpcError::UnknownMethod(m))) => Err(ClientError::Unsupported(m)),
            Ok(Err(err)) => Err(ClientError::HostError(err.to_string())),
            Ok(Ok(value)) => Ok(value),
        }
    }

    /// Fire-and-forget `Mutate` (the local optimistic write already landed).
    pub(crate) fn mutate(self: &Arc<Self>, op: serde_json::Value) {
        let host = self.clone();
        crate::runtime::shared().spawn(async move {
            for attempt in 0..3u64 {
                match host.call(zeron_rpc::methods::MUTATE, op.clone()).await {
                    Ok(_) => return,
                    Err(ClientError::HostUnavailable(_)) => {
                        tokio::time::sleep(Duration::from_secs(2 * (attempt + 1))).await;
                    }
                    Err(err) => {
                        tracing::warn!(error = %err, op = %op, "direct Mutate failed");
                        return;
                    }
                }
            }
        });
    }

    // ── registry mirror ────────────────────────────────────────────────────

    async fn watch_registry(self: &Arc<Self>, link: &Arc<Link>, method: &'static str) {
        let mut sub = match link
            .rpc
            .subscribe_scoped(method, serde_json::json!({}))
            .await
        {
            Ok(sub) => sub,
            Err(err) => {
                tracing::warn!(method, error = %err, "direct watch failed");
                return;
            }
        };
        loop {
            let item = tokio::select! {
                _ = link.cancel.cancelled() => return,
                item = sub.recv() => item,
            };
            let Some(value) = item else { return };
            let Some(client) = self.client.upgrade() else {
                return;
            };
            let applied = match method {
                zeron_rpc::methods::WATCH_DEVICES => serde_json::from_value::<Vec<Device>>(value)
                    .map(|rows| self.mirror_devices(&client, rows)),
                zeron_rpc::methods::WATCH_SPACES => serde_json::from_value::<Vec<Space>>(value)
                    .map(|rows| self.mirror_spaces(&client, rows)),
                zeron_rpc::methods::WATCH_CHATS => serde_json::from_value::<Vec<Chat>>(value)
                    .map(|rows| self.mirror_chats(&client, rows)),
                _ => serde_json::from_value::<Vec<Session>>(value)
                    .map(|rows| self.mirror_sessions(&client, rows)),
            };
            match applied {
                Ok(true) => {
                    self.settle_registry(&client);
                    client.mark_direct_synced();
                    client.recompute_workspace();
                }
                Ok(false) => client.mark_direct_synced(),
                Err(err) => tracing::warn!(method, error = %err, "direct watch: bad item"),
            }
        }
    }

    fn recently_local(&self, id: &str) -> bool {
        let now = now_ms();
        let mut rows = lock(&self.local_rows);
        rows.retain(|_, at| now - *at < LOCAL_ROW_GRACE_MS);
        rows.contains_key(id)
    }

    fn mirror_devices(&self, client: &ClientInner, rows: Vec<Device>) -> bool {
        let (state, _) = client.workspace.state();
        let changed: Vec<&Device> = rows
            .iter()
            .filter(|row| !state.devices.iter().any(|d| d == *row))
            .collect();
        if changed.is_empty() {
            return false;
        }
        client.workspace.mutate(|doc| {
            for row in changed {
                if let Err(err) = doc.upsert_device(row) {
                    tracing::warn!(error = %err, "mirror device");
                }
            }
        });
        true
    }

    fn mirror_spaces(&self, client: &ClientInner, rows: Vec<Space>) -> bool {
        let (state, _) = client.workspace.state();
        let changed: Vec<&Space> = rows
            .iter()
            .filter(|row| !state.spaces.iter().any(|s| s == *row))
            .collect();
        let gone: Vec<String> = state
            .spaces
            .iter()
            .filter(|s| !rows.iter().any(|r| r.id == s.id) && !self.recently_local(&s.id))
            .map(|s| s.id.clone())
            .collect();
        if changed.is_empty() && gone.is_empty() {
            return false;
        }
        client.workspace.mutate(|doc| {
            for row in changed {
                if let Err(err) = doc.upsert_space(row) {
                    tracing::warn!(error = %err, "mirror space");
                }
            }
            for id in gone {
                let _ = doc.delete_space(&id);
            }
        });
        true
    }

    fn mirror_chats(&self, client: &ClientInner, rows: Vec<Chat>) -> bool {
        let (state, _) = client.workspace.state();
        let changed: Vec<&Chat> = rows
            .iter()
            .filter(|row| !state.chats.iter().any(|c| c == *row))
            .collect();
        let gone: Vec<String> = state
            .chats
            .iter()
            .filter(|c| !rows.iter().any(|r| r.id == c.id) && !self.recently_local(&c.id))
            .map(|c| c.id.clone())
            .collect();
        if changed.is_empty() && gone.is_empty() {
            return false;
        }
        client.workspace.mutate(|doc| {
            for row in changed {
                if let Err(err) = doc.upsert_chat(row) {
                    tracing::warn!(error = %err, "mirror chat");
                }
            }
            for id in gone {
                let _ = doc.delete_chat(&id);
            }
        });
        true
    }

    fn mirror_sessions(&self, client: &ClientInner, rows: Vec<Session>) -> bool {
        let (state, _) = client.workspace.state();
        let changed: Vec<&Session> = rows
            .iter()
            .filter(|row| !state.sessions.iter().any(|s| s == *row))
            .collect();
        if changed.is_empty() {
            return false;
        }
        client.workspace.mutate(|doc| {
            for row in changed {
                if let Err(err) = doc.upsert_session(row) {
                    tracing::warn!(error = %err, "mirror session");
                }
            }
        });
        true
    }

    // ── transcripts ────────────────────────────────────────────────────────

    /// Direct sessions start from an empty shadow doc; the engine's reset
    /// fills it.
    pub(crate) fn session_doc(&self, chat_id: &str) -> Result<SessionDoc> {
        SessionDoc::init(chat_id).map_err(doc_err)
    }

    pub(crate) fn session_opened(self: &Arc<Self>, core: &Arc<SessionCore>) {
        if let Some(link) = self.current_link() {
            let mirrors = lock(&self.mirrors);
            if let Some((existing, token)) = mirrors.get(&core.chat_id)
                && !token.is_cancelled()
                && existing.upgrade().is_some_and(|c| Arc::ptr_eq(&c, core))
            {
                return;
            }
            drop(mirrors);
            self.restart_mirror(core, &link);
        }
    }

    fn restart_mirror(self: &Arc<Self>, core: &Arc<SessionCore>, link: &Arc<Link>) {
        let token = link.cancel.child_token();
        if let Some((_, old)) =
            lock(&self.mirrors).insert(core.chat_id.clone(), (Arc::downgrade(core), token.clone()))
        {
            old.cancel();
        }
        let weak = Arc::downgrade(core);
        let link = link.clone();
        let chat_id = core.chat_id.clone();
        crate::runtime::shared().spawn(async move {
            mirror_transcript(link, weak, chat_id, token).await;
        });
    }

    // ── commands ───────────────────────────────────────────────────────────

    pub(crate) fn on_command(self: &Arc<Self>, chat_id: &str) {
        let host = self.clone();
        let chat_id = chat_id.to_owned();
        crate::runtime::shared().spawn(async move { host.drain(&chat_id).await });
    }

    async fn drain(self: &Arc<Self>, chat_id: &str) {
        let gate = lock(&self.drains)
            .entry(chat_id.to_owned())
            .or_default()
            .clone();
        let _serial = gate.lock().await;
        let Some(client) = self.client.upgrade() else {
            return;
        };
        let Some(core) = client.session_core(chat_id) else {
            return;
        };
        let device_id = client.config.device_id.clone();
        drop(client);
        let now = now_ms();
        let pending: Vec<_> = core
            .doc()
            .read_commands()
            .unwrap_or_default()
            .into_iter()
            .filter(|c| {
                c.issued_by == device_id
                    && c.status == SessionCommandStatus::Pending
                    && now < c.effective_expiry()
            })
            .collect();
        for command in pending {
            let params = serde_json::json!({ "chatId": chat_id, "entry": command });
            // RelayCommand is idempotent on the command id: retry freely.
            let mut outcome = None;
            for attempt in 0..3u64 {
                match self
                    .call(zeron_rpc::methods::RELAY_COMMAND, params.clone())
                    .await
                {
                    Ok(value) => {
                        outcome = Some(
                            value
                                .get("outcome")
                                .and_then(|v| v.as_str())
                                .unwrap_or("executed")
                                .to_owned(),
                        );
                        break;
                    }
                    Err(ClientError::HostUnavailable(_)) => {
                        // Stays pending; the next link replays it.
                        if attempt == 2 {
                            return;
                        }
                        tokio::time::sleep(Duration::from_secs(1 + attempt)).await;
                    }
                    Err(err) => {
                        let message = err.to_string();
                        let _ = core.write(|doc| {
                            doc.set_command_status(
                                &command.id,
                                SessionCommandStatus::Rejected,
                                Some(&message),
                            )
                        });
                        outcome = None;
                        break;
                    }
                }
            }
            let Some(outcome) = outcome else { continue };
            let status = match outcome.as_str() {
                "expired" => SessionCommandStatus::Expired,
                "superseded" => SessionCommandStatus::Superseded,
                _ => SessionCommandStatus::Applied,
            };
            let _ = core.write(|doc| doc.set_command_status(&command.id, status, None));
        }
    }
}

/// Stream one chat's transcript into its shadow doc until the link drops,
/// the core is evicted, or the mirror is replaced.
async fn mirror_transcript(
    link: Arc<Link>,
    core: Weak<SessionCore>,
    chat_id: String,
    token: CancellationToken,
) {
    // What the shadow doc holds, entry by entry (index = list position).
    // Seeded from the doc: after a reconnect it already holds the transcript,
    // and starting from empty would append every entry a second time.
    let mut written: Vec<SessionMessageEntry> = match core.upgrade() {
        Some(core) => core.doc().read_entries().unwrap_or_default(),
        None => return,
    };
    loop {
        let params = serde_json::json!({ "chatId": chat_id });
        let mut sub = match link
            .rpc
            .subscribe_scoped(zeron_rpc::methods::WATCH_DOC_MESSAGES, params)
            .await
        {
            Ok(sub) => sub,
            Err(_) => return,
        };
        let mut entries: Vec<SessionMessageEntry> = Vec::new();
        loop {
            let item = tokio::select! {
                _ = token.cancelled() => return,
                item = sub.recv() => item,
            };
            let Some(value) = item else {
                // Stream closed (link gone, or the engine dropped it).
                if token.is_cancelled() || link.cancel.is_cancelled() {
                    return;
                }
                break;
            };
            let Some(core) = core.upgrade() else { return };
            let update: TranscriptUpdate = match serde_json::from_value(value) {
                Ok(update) => update,
                Err(err) => {
                    tracing::warn!(error = %err, "direct transcript: bad frame");
                    continue;
                }
            };
            if let Err(err) = apply_transcript_frame(&mut entries, update.frame) {
                tracing::info!(error = %err, "direct transcript desync; resubscribing");
                break;
            }
            if let Err(err) = core.write(|doc| reconcile(doc, &mut written, &entries)) {
                tracing::warn!(error = %err, "direct transcript write failed");
                written.clear();
                let _ = core.write(|doc| doc.truncate_messages(0));
                break;
            }
            if let Some(usage) = update.context_usage {
                let _ = core.write(|doc| doc.update_context_usage(usage.tokens, usage.window));
            }
            core.set_hydrated();
            core.schedule_refresh();
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

/// Bring the shadow doc from `written` to `target` with minimal writes:
/// streaming text grows through `SegmentWriter` (LoroText appends), other
/// edits rewrite one entry, and anything non-prefix rebuilds.
fn reconcile(
    doc: &SessionDoc,
    written: &mut Vec<SessionMessageEntry>,
    target: &[SessionMessageEntry],
) -> std::result::Result<(), zeron_doc::DocError> {
    let common = written
        .iter()
        .zip(target)
        .take_while(|(w, t)| w.id == t.id)
        .count();
    if common < written.len() {
        doc.truncate_messages(common)?;
        written.truncate(common);
    }
    for (i, next) in target.iter().enumerate() {
        match written.get(i) {
            None => {
                doc.push_message(next)?;
                written.push(next.clone());
            }
            Some(prev) if prev == next => {}
            Some(prev) => {
                let appendable = prev.parts.len() <= next.parts.len()
                    && prev.parts.iter().zip(&next.parts).all(|(a, b)| {
                        a.id() == b.id() && std::mem::discriminant(a) == std::mem::discriminant(b)
                    });
                if appendable {
                    if prev.parts != next.parts {
                        let mut writer = SegmentWriter::resume(doc, i, prev.parts.clone());
                        writer.sync(&next.parts)?;
                    }
                    if prev.status != next.status
                        || prev.duration_ms != next.duration_ms
                        || prev.created_at != next.created_at
                    {
                        doc.set_entry_scalars(i, next)?;
                    }
                } else {
                    doc.replace_message(i, next)?;
                }
                written[i] = next.clone();
            }
        }
    }
    Ok(())
}
