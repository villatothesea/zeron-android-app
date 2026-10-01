//! Direct mode against an in-process engine that serves a sanitized capture
//! of a real 0.2.97 desktop engine in local mode (40 chats, 23 archived, 11
//! spaces, 13 sessions with running turns, Windows paths), plus version
//! drift and a silent engine.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use zeron_proto::Chat;
use zeron_rpc::{RpcError, RpcReply, RpcService};

use super::host::{TEST_ENGINES, engine_notice};
use super::lenient::{decode_rows, parse_version, sanitize_transcript_update, unsupported_text};
use super::{DirectPhase, SshAuth, SshEndpoint, SshError, SshTarget};
use crate::events::NullListener;
use crate::{Client, ClientConfig, Credentials, lock};

fn fixture(name: &str) -> serde_json::Value {
    let text = match name {
        "EngineInfo" => include_str!("fixtures/EngineInfo.json"),
        "WatchDevices" => include_str!("fixtures/WatchDevices.json"),
        "WatchSpaces" => include_str!("fixtures/WatchSpaces.json"),
        "WatchChats" => include_str!("fixtures/WatchChats.json"),
        "WatchSessions" => include_str!("fixtures/WatchSessions.json"),
        // Captured from a real 0.2.100 engine whose Codex catalog has a
        // custom model (gpt-6.1-sol) on top.
        "ListHarnesses" => include_str!("fixtures/ListHarnesses.json"),
        "ListModels" => include_str!("fixtures/ListModels.codex.json"),
        other => panic!("no fixture {other}"),
    };
    serde_json::from_str(text).expect("fixture json")
}

/// Fields a newer engine might add or change.
fn drifted(method: &str, mut value: serde_json::Value) -> serde_json::Value {
    let rows = value.as_array_mut().expect("list");
    for row in rows.iter_mut() {
        row["futureField"] = serde_json::json!({"nested": [1, 2, 3]});
    }
    match method {
        // One chat loses a required field: it must be skipped, not wipe the
        // snapshot (and must not be deleted from the replica either).
        "WatchChats" => {
            let broken = rows
                .iter_mut()
                .find(|r| r["archived"] == false)
                .expect("active chat");
            broken.as_object_mut().unwrap().remove("createdAt");
        }
        // An unknown status value on one session.
        "WatchSessions" => rows[0]["status"] = serde_json::json!("hibernating"),
        _ => {}
    }
    if method == "WatchChats" {
        // A harness and a reasoning level this app has never heard of: the
        // chat must stay, just without the parts it can't read.
        let active: Vec<usize> = rows
            .iter()
            .enumerate()
            .filter(|(_, r)| r["archived"] == false && r.get("createdAt").is_some())
            .map(|(i, _)| i)
            .collect();
        rows[active[0]]["config"]["harness"] = serde_json::json!("nova-agent");
        rows[active[1]]["config"]["reasoning"] = serde_json::json!("ludicrous");
    }
    value
}

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Real,
    Drifted,
    /// Answers EngineInfo and every watch but WatchChats.
    SilentChats,
    /// Running sessions heartbeat "now", stamped the way a Windows engine
    /// writes them (100 ns precision: 7 fractional digits).
    FreshSessions,
    /// Like FreshSessions from a computer whose clock is 2 minutes behind
    /// the phone, heartbeating every 100 ms.
    LaggingClock,
    /// `ListModels` fails the way a busy engine does.
    BrokenModels,
    /// `WatchDocMessages` honours `openingTail`: the newest entry first
    /// (`historyPending`), the complete transcript 600 ms later. Later
    /// subscriptions to the same chat send only the tail.
    OpeningTail,
}

fn transcript_entry(id: &str) -> serde_json::Value {
    serde_json::to_value(zeron_doc::SessionMessageEntry {
        id: id.into(),
        role: zeron_doc::MessageRole::Assistant,
        parts: vec![zeron_doc::MessagePart::Text {
            id: format!("{id}-t"),
            text: format!("text of {id}"),
        }],
        created_at: 1,
        device_id: "pc".into(),
        status: None,
        continuation_of: None,
        duration_ms: None,
    })
    .unwrap()
}

/// The engine, and every `ListModels` params it was sent (in order).
struct Engine(Mode, Arc<std::sync::Mutex<Vec<serde_json::Value>>>);

#[async_trait::async_trait]
impl RpcService for Engine {
    async fn handle(&self, method: &str, params: serde_json::Value) -> Result<RpcReply, RpcError> {
        if method == "EngineInfo" {
            return Ok(RpcReply::Value(fixture("EngineInfo")));
        }
        if method == "ListDrives" {
            return Ok(RpcReply::Value(serde_json::json!({
                "drives": [{ "name": "C:", "path": "C:\\" }, { "name": "D:", "path": "D:\\" }]
            })));
        }
        if method == "ListAgentAccounts" {
            // Echo the force flag in the plan label so the test sees it arrive.
            let force = params["forceUsage"].as_bool().unwrap_or(false);
            return Ok(RpcReply::Value(serde_json::json!({
                "accounts": [{
                    "id": "slot", "harness": "codex", "active": true,
                    "planLabel": if force { "Plus (forced)" } else { "Plus" },
                    "usageWindows": [{ "label": "5-hour", "usedFraction": 0.25, "resetsAt": null }]
                }],
                "warnings": []
            })));
        }
        if method == "ReadWorkspaceFile" {
            lock(&self.1).push(params.clone());
            return Ok(RpcReply::Value(serde_json::json!({
                "checkoutId": "co", "path": params["path"], "text": "# Report\n", "size": 9,
                "encoding": "utf8", "truncated": false
            })));
        }
        if method == "WatchDocMessages" {
            // Record who streams which transcript, and when it stops.
            struct Unwatch(
                Arc<std::sync::Mutex<Vec<serde_json::Value>>>,
                serde_json::Value,
            );
            impl Drop for Unwatch {
                fn drop(&mut self) {
                    lock(&self.0).push(serde_json::json!({ "unwatch": self.1 }));
                }
            }
            let chat = params["chatId"].clone();
            let earlier = lock(&self.1)
                .iter()
                .filter(|v| v.get("watch") == Some(&chat))
                .count();
            lock(&self.1).push(serde_json::json!({
                "watch": chat, "openingTail": params["openingTail"].clone()
            }));
            let guard = Unwatch(self.1.clone(), chat);
            let opening: Vec<serde_json::Value> = if self.0 == Mode::OpeningTail {
                let tail = serde_json::json!({
                    "reset": [transcript_entry("m3")], "historyPending": true
                });
                let full = serde_json::json!({
                    "reset": [transcript_entry("m1"), transcript_entry("m2"), transcript_entry("m3")]
                });
                if earlier == 0 {
                    vec![tail, full]
                } else {
                    vec![tail]
                }
            } else {
                Vec::new()
            };
            let frames = futures::stream::iter(opening.into_iter().enumerate()).then(
                |(i, frame)| async move {
                    if i > 0 {
                        tokio::time::sleep(Duration::from_millis(600)).await;
                    }
                    frame
                },
            );
            return Ok(RpcReply::Stream(
                frames
                    .chain(futures::stream::pending::<serde_json::Value>())
                    .map(move |v| {
                        let _ = &guard;
                        v
                    })
                    .boxed(),
            ));
        }
        if method == "ListHarnesses" {
            return Ok(RpcReply::Value(fixture("ListHarnesses")));
        }
        if method == "ListModels" {
            lock(&self.1).push(params);
            if self.0 == Mode::BrokenModels {
                return Err(RpcError::Failed("model catalog unavailable; retry".into()));
            }
            let mut list = fixture("ListModels");
            if self.0 == Mode::Drifted {
                let rows = list.as_array_mut().unwrap();
                // A newer engine's option kind (a free-form toggle: no
                // choices, no default) on the top model…
                rows[0]["options"]
                    .as_array_mut()
                    .unwrap()
                    .push(serde_json::json!({"id": "turbo", "label": "Turbo", "kind": "toggle"}));
                // …and a row this app can't read at all.
                rows.push(serde_json::json!({"id": "gpt-7-preview", "label": {"en": "GPT-7"}}));
            }
            return Ok(RpcReply::Value(list));
        }
        if !matches!(
            method,
            "WatchDevices" | "WatchSpaces" | "WatchChats" | "WatchSessions"
        ) {
            return Err(RpcError::UnknownMethod(method.into()));
        }
        if self.0 == Mode::SilentChats && method == "WatchChats" {
            return Ok(RpcReply::Stream(futures::stream::pending().boxed()));
        }
        let mut item = fixture(method);
        if self.0 == Mode::Drifted {
            item = drifted(method, item);
        }
        if self.0 == Mode::LaggingClock && method == "WatchSessions" {
            let base = item.clone();
            let beats = futures::stream::unfold(0u32, move |n| {
                let mut frame = base.clone();
                async move {
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    let lagging = chrono::Utc::now() - chrono::TimeDelta::seconds(120);
                    for row in frame.as_array_mut().unwrap() {
                        if row["status"] == "working" {
                            row["updatedAt"] = serde_json::json!(lagging.to_rfc3339());
                        }
                    }
                    Some((frame, n + 1))
                }
            });
            return Ok(RpcReply::Stream(beats.boxed()));
        }
        if self.0 == Mode::FreshSessions && method == "WatchSessions" {
            let now = chrono::Utc::now()
                .format("%Y-%m-%dT%H:%M:%S%.9fZ")
                .to_string();
            let windows = format!("{}00Z", &now[..now.len() - 3]);
            for row in item.as_array_mut().unwrap() {
                if row["status"] == "working" {
                    row["updatedAt"] = serde_json::json!(windows);
                }
            }
        }
        // Snapshot, then a few quick re-emissions (running turns tick).
        let frames: Vec<serde_json::Value> = (0..4).map(|_| item.clone()).collect();
        Ok(RpcReply::Stream(
            futures::stream::iter(frames)
                .then(|f| async move {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                    f
                })
                .chain(futures::stream::pending())
                .boxed(),
        ))
    }
}

async fn start_engine(host: &str, mode: Mode) -> Arc<std::sync::Mutex<Vec<serde_json::Value>>> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", listener.local_addr().unwrap());
    let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
    crate::runtime::shared().spawn(zeron_rpc::serve_ws_listener(
        listener,
        Arc::new(Engine(mode, seen.clone())),
    ));
    lock(&TEST_ENGINES)
        .get_or_insert_with(Default::default)
        .insert(host.to_owned(), url);
    seen
}

fn direct_client(host: &str, dir: &std::path::Path) -> Client {
    direct_client_at(host, Vec::new(), dir)
}

fn endpoint(host: &str, kind: &str, head_start_ms: u32) -> SshEndpoint {
    SshEndpoint {
        host: host.into(),
        port: 22,
        kind: kind.into(),
        head_start_ms,
        connect_timeout_ms: 0,
    }
}

fn direct_client_at(host: &str, endpoints: Vec<SshEndpoint>, dir: &std::path::Path) -> Client {
    let target = SshTarget {
        host: host.to_owned(),
        port: 22,
        user: "dev".into(),
        auth: SshAuth::Password {
            password: String::new(),
        },
        engine_port: 27654,
        host_key_fingerprint: Some("SHA256:test".into()),
        endpoints,
    };
    let mut config = ClientConfig::new("https://edge.invalid", dir);
    config.device_id = "android-test".into();
    config.platform = "android".into();
    Client::new(config, Credentials::Direct(target), Arc::new(NullListener)).expect("client")
}

async fn wait_for(client: &Client, what: &str, ok: impl Fn(&Client) -> bool) {
    for _ in 0..200 {
        if ok(client) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("timed out waiting for {what}: {:?}", client.direct_status());
}

#[test]
fn real_shape_frames_decode_without_skips() {
    for method in ["WatchDevices", "WatchSpaces", "WatchChats", "WatchSessions"] {
        let value = fixture(method);
        let n = value.as_array().unwrap().len();
        let errors = match method {
            "WatchDevices" => {
                decode_rows::<zeron_proto::Device>(value, &[])
                    .unwrap()
                    .errors
            }
            "WatchSpaces" => {
                decode_rows::<zeron_proto::Space>(value, &[])
                    .unwrap()
                    .errors
            }
            "WatchChats" => decode_rows::<Chat>(value, &[]).unwrap().errors,
            _ => {
                decode_rows::<zeron_proto::Session>(value, &[])
                    .unwrap()
                    .errors
            }
        };
        assert!(errors.is_empty(), "{method}: {errors:?}");
        assert!(n > 0);
    }
}

#[test]
fn drifted_rows_are_skipped_not_fatal() {
    let decoded = decode_rows::<Chat>(drifted("WatchChats", fixture("WatchChats")), &[]).unwrap();
    assert_eq!(decoded.rows.len(), 39);
    assert_eq!(decoded.errors.len(), 1, "{:?}", decoded.errors);
    assert_eq!(decoded.repaired.len(), 2, "{:?}", decoded.repaired);
    assert!(
        decoded
            .repaired
            .iter()
            .any(|r| r.ends_with("ignored config.reasoning"))
    );
    assert!(
        decoded
            .repaired
            .iter()
            .any(|r| r.ends_with("ignored config"))
    );
    assert!(
        decoded.rows.iter().any(|c| c.config.is_none()),
        "unknown-harness chat kept"
    );
    assert_eq!(decoded.ids.len(), 40, "the broken row's id still guards it");
    // Wrapped lists and junk frames.
    let wrapped = serde_json::json!({ "chats": fixture("WatchChats"), "cursor": 3 });
    assert_eq!(decode_rows::<Chat>(wrapped, &[]).unwrap().rows.len(), 40);
    assert!(decode_rows::<Chat>(serde_json::json!("nope"), &[]).is_err());
    // Unknown session status → idle, row kept.
    let sessions = decode_rows::<zeron_proto::Session>(
        drifted("WatchSessions", fixture("WatchSessions")),
        &[("status", serde_json::json!("idle"))],
    )
    .unwrap();
    assert!(sessions.errors.is_empty(), "{:?}", sessions.errors);
    assert_eq!(sessions.rows[0].status, zeron_proto::SessionStatus::Idle);
}

#[tokio::test(flavor = "multi_thread")]
async fn syncs_the_real_engine_shape() {
    start_engine("real.test", Mode::Real).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("real.test", dir.path());
    wait_for(&client, "live", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    wait_for(&client, "rows", |c| {
        let ws = c.workspace();
        ws.archived.len() == 23 && !ws.front.recent.is_empty()
    })
    .await;
    let ws = client.workspace();
    assert_eq!(ws.devices.len(), 1);
    assert!(ws.projects.len() >= 10, "projects: {}", ws.projects.len());
    let status = client.direct_status().unwrap();
    // EngineInfo has no version; the engine's Device row supplies it.
    assert_eq!(status.engine_version.as_deref(), Some("0.2.97"));
    assert!(status.notice.is_none());
    assert!(status.last_error.is_none(), "{status:?}");
    assert!(
        status
            .streams
            .iter()
            .all(|s| s.frames > 0 && s.skipped_rows == 0)
    );
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn tolerates_version_drift() {
    start_engine("drift.test", Mode::Drifted).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("drift.test", dir.path());
    wait_for(&client, "live", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    wait_for(&client, "rows", |c| c.workspace().archived.len() == 23).await;
    let status = client.direct_status().unwrap();
    let chats = status
        .streams
        .iter()
        .find(|s| s.name == "WatchChats")
        .unwrap();
    assert_eq!(
        (chats.rows, chats.skipped_rows, chats.repaired_rows),
        (39, 1, 2)
    );
    let sessions = status
        .streams
        .iter()
        .find(|s| s.name == "WatchSessions")
        .unwrap();
    assert_eq!((sessions.skipped_rows, sessions.repaired_rows), (0, 1));
    assert!(
        chats
            .error
            .as_deref()
            .is_some_and(|e| e.contains("skipped 1"))
    );
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_silent_stream_is_reported_not_blank() {
    start_engine("silent.test", Mode::SilentChats).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("silent.test", dir.path());
    wait_for(&client, "sync timeout", |c| {
        c.direct_status().is_some_and(|s| {
            s.last_error
                .as_deref()
                .is_some_and(|e| e.contains("WatchChats"))
        })
    })
    .await;
    let status = client.direct_status().unwrap();
    assert_ne!(status.phase, DirectPhase::Live);
    assert!(
        status
            .log
            .iter()
            .any(|l| l.message.contains("no WatchChats")),
        "{:?}",
        status.log
    );
    client.shutdown();
}

/// A transcript entry as a future engine might send it.
fn future_entry() -> serde_json::Value {
    serde_json::json!({
        "id": "m1",
        "role": "narrator",
        "status": "paused",
        "createdAt": 1_700_000_000_000i64,
        "deviceId": "d1",
        "mood": "curious",
        "parts": [
            { "kind": "text", "id": "p1", "text": "hello", "lang": "en" },
            { "kind": "canvas", "id": "p2", "shapes": [1, 2] },
            { "kind": "tool", "id": "p3", "call": { "kind": "browser", "url": "https://x" }, "resolved": true },
            { "kind": "tool", "id": "p4", "call": { "kind": "exec", "command": "ls" }, "subagentStatus": "queued" },
            { "kind": "reasoning", "id": "p5", "text": "hmm" }
        ]
    })
}

#[test]
fn unknown_transcript_kinds_render_as_fallbacks() {
    let mut update = serde_json::json!({
        "reset": [future_entry()],
        "contextUsage": { "tokens": "lots" },
        "cursor": 7
    });
    let repaired = sanitize_transcript_update(&mut update);
    assert!(repaired >= 4, "repaired {repaired}");
    let update: zeron_doc::TranscriptUpdate = serde_json::from_value(update).expect("readable");
    let zeron_doc::transcript_delta::TranscriptFrame::Reset { reset } = update.frame else {
        panic!("reset frame");
    };
    let entry = &reset[0];
    assert_eq!(entry.role, zeron_doc::MessageRole::Assistant);
    assert_eq!(entry.status, None);
    assert_eq!(entry.parts.len(), 5, "no part is dropped");
    use zeron_doc::MessagePart as P;
    assert!(matches!(&entry.parts[0], P::Text { text, .. } if text == "hello"));
    assert!(matches!(&entry.parts[1], P::Text { text, .. } if *text == unsupported_text("canvas")));
    assert!(matches!(
        &entry.parts[2],
        P::Tool { call: zeron_proto::ToolCall::Unknown { name, .. }, resolved: true, .. } if name == "browser"
    ));
    assert!(matches!(
        &entry.parts[3],
        P::Tool {
            call: zeron_proto::ToolCall::Exec { .. },
            subagent_status: None,
            ..
        }
    ));
    assert!(matches!(&entry.parts[4], P::Reasoning { .. }));
    assert!(update.context_usage.is_none());
}

#[test]
fn unknown_kinds_in_deltas_and_broken_entries() {
    let mut update = serde_json::json!({
        "upsert": [
            { "after": null, "entry": future_entry() },
            { "after": "m1", "entry": { "id": "m2", "parts": "not a list" } }
        ],
        "append": [],
        "remove": [],
        "count": 2
    });
    sanitize_transcript_update(&mut update);
    let update: zeron_doc::TranscriptUpdate = serde_json::from_value(update).expect("readable");
    let zeron_doc::transcript_delta::TranscriptFrame::Delta { upsert, .. } = update.frame else {
        panic!("delta frame");
    };
    assert_eq!(
        upsert.len(),
        2,
        "a broken entry becomes a placeholder, keeping the count"
    );
    assert_eq!(upsert[1].entry.id, "m2");
    // Known frames are untouched.
    let mut plain = serde_json::json!({ "reset": [] });
    assert_eq!(sanitize_transcript_update(&mut plain), 0);
}

#[test]
fn newer_minor_engines_get_a_notice_patches_do_not() {
    assert_eq!(parse_version("0.2.98"), Some((0, 2, 98)));
    assert_eq!(parse_version("v0.3.0-beta.1"), Some((0, 3, 0)));
    assert_eq!(parse_version("1"), Some((1, 0, 0)));
    assert_eq!(parse_version("nightly"), None);
    assert!(engine_notice(Some("0.2.97")).is_none());
    assert!(engine_notice(Some("0.2.140")).is_none());
    assert!(engine_notice(Some("0.3.0")).is_some_and(|n| n.contains("0.3.0")));
    assert!(engine_notice(Some("garbage")).is_none());
    assert!(engine_notice(None).is_none());
}

#[tokio::test(flavor = "multi_thread")]
async fn direct_pins_are_phone_only_and_start_empty() {
    start_engine("pins.test", Mode::Real).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("pins.test", dir.path());
    wait_for(&client, "rows", |c| {
        let ws = c.workspace();
        ws.pins_ready && !ws.front.recent.is_empty()
    })
    .await;
    let ws = client.workspace();
    assert!(ws.front.pinned.is_empty(), "fresh direct link has no pins");
    let id = ws.front.recent[0].id.clone();
    assert!(!ws.session(&id).unwrap().pinned);
    client.pin_session(&id).unwrap();
    wait_for(&client, "pinned", |c| {
        c.workspace().session(&id).is_some_and(|r| r.pinned)
    })
    .await;
    let other = client
        .workspace()
        .front
        .recent
        .first()
        .map(|r| r.id.clone());
    if let Some(other) = other {
        assert!(!client.workspace().session(&other).unwrap().pinned);
    }
    client.unpin_session(&id).unwrap();
    wait_for(&client, "unpinned", |c| {
        c.workspace().session(&id).is_some_and(|r| !r.pinned)
    })
    .await;
    assert!(client.workspace().front.pinned.is_empty());
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn lists_windows_drives_over_the_direct_link() {
    start_engine("drives.test", Mode::Real).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("drives.test", dir.path());
    wait_for(&client, "live", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    let device = client.workspace().devices[0].id.clone();
    let drives = client.list_drives(&device).await.unwrap();
    let paths: Vec<&str> = drives.iter().map(|d| d.path.as_str()).collect();
    assert_eq!(paths, ["C:\\", "D:\\"]);
    // Plan usage travels the same link (ListAgentAccounts).
    let usage = client.list_agent_usage(&device, true).await.unwrap();
    assert_eq!(usage.len(), 1);
    assert_eq!(usage[0].plan_label.as_deref(), Some("Plus (forced)"));
    assert_eq!(usage[0].windows[0].used_fraction, 0.25);
    // Methods the engine lacks surface as Unsupported, not a dead link.
    assert!(matches!(
        client.list_folders(&device, None).await,
        Err(crate::ClientError::Unsupported(_))
    ));
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn running_sessions_read_as_working() {
    start_engine("fresh.test", Mode::FreshSessions).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("fresh.test", dir.path());
    wait_for(&client, "rows", |c| {
        !c.workspace().front.recent.is_empty() || !c.workspace().projects.is_empty()
    })
    .await;
    let running: Vec<String> = fixture("WatchSessions")
        .as_array()
        .unwrap()
        .iter()
        .filter(|r| r["status"] == "working")
        .map(|r| r["chatId"].as_str().unwrap().to_owned())
        .collect();
    wait_for(&client, "working rows", |c| {
        let ws = c.workspace();
        running.iter().all(|id| {
            ws.sessions
                .get(id)
                .is_some_and(|r| r.indicator == zeron_proto::ChatIndicator::Working)
        })
    })
    .await;
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_computer_clock_behind_the_phone_still_reads_working() {
    start_engine("lagging.test", Mode::LaggingClock).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("lagging.test", dir.path());
    let running: Vec<String> = fixture("WatchSessions")
        .as_array()
        .unwrap()
        .iter()
        .filter(|r| r["status"] == "working")
        .map(|r| r["chatId"].as_str().unwrap().to_owned())
        .collect();
    wait_for(&client, "working rows despite a 2 min clock gap", |c| {
        let ws = c.workspace();
        running.iter().all(|id| {
            ws.sessions
                .get(id)
                .is_some_and(|r| r.indicator == zeron_proto::ChatIndicator::Working)
        })
    })
    .await;
    client.shutdown();
}

// ── several addresses per machine ──────────────────────────────────────────

fn active_kind(client: &Client) -> Option<String> {
    client
        .direct_status()?
        .endpoints
        .into_iter()
        .find(|e| e.active)
        .map(|e| e.kind)
}

fn test_double(host: &str, behaviour: &str) {
    lock(&TEST_ENGINES)
        .get_or_insert_with(Default::default)
        .insert(host.to_owned(), behaviour.to_owned());
}

#[tokio::test(flavor = "multi_thread")]
async fn a_silent_lan_address_hands_over_to_tailscale_after_its_head_start() {
    test_double("lan-hang.test", "hang");
    start_engine("ts-a.test", Mode::Real).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client_at(
        "lan-hang.test",
        vec![
            endpoint("lan-hang.test", "lan", 150),
            endpoint("ts-a.test", "tailscale", 0),
        ],
        dir.path(),
    );
    let started = std::time::Instant::now();
    wait_for(&client, "live over tailscale", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    assert!(started.elapsed() < Duration::from_secs(5));
    assert_eq!(active_kind(&client).as_deref(), Some("tailscale"));
    let status = client.direct_status().unwrap();
    let lan = &status.endpoints[0];
    assert!(lan.last_attempt_ms.is_some() && lan.last_ok_ms.is_none() && !lan.active);
    assert!(status.endpoints[1].latency_ms.is_some());
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_silent_address_with_a_short_timeout_fails_fast() {
    test_double("lan-silent.test", "hang");
    test_double("ts-off.test", "hang");
    let dir = tempfile::tempdir().unwrap();
    let mut lan = endpoint("lan-silent.test", "lan", 100);
    lan.connect_timeout_ms = 300;
    let mut ts = endpoint("ts-off.test", "tailscale", 0);
    ts.connect_timeout_ms = 600;
    let client = direct_client_at("lan-silent.test", vec![lan, ts], dir.path());
    let started = std::time::Instant::now();
    wait_for(&client, "failed", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Failed)
    })
    .await;
    // Both gave up on their own clock instead of the 20 s default.
    assert!(started.elapsed() < Duration::from_secs(5));
    let status = client.direct_status().unwrap();
    for e in &status.endpoints {
        assert!(
            e.last_error
                .as_deref()
                .unwrap()
                .contains("timed out reaching"),
            "{e:?}"
        );
    }
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_refused_address_moves_on_without_waiting_out_its_head_start() {
    test_double("lan-refuse.test", "refuse");
    start_engine("ts-b.test", Mode::Real).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client_at(
        "lan-refuse.test",
        vec![
            endpoint("lan-refuse.test", "lan", 60_000),
            endpoint("ts-b.test", "tailscale", 0),
        ],
        dir.path(),
    );
    wait_for(&client, "live over tailscale", |c| {
        active_kind(c).as_deref() == Some("tailscale")
    })
    .await;
    let status = client.direct_status().unwrap();
    assert!(
        status.endpoints[0]
            .last_error
            .as_deref()
            .unwrap()
            .contains("refused")
    );
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn another_device_on_the_lan_ip_is_not_reported_as_a_changed_host_key() {
    test_double("lan-stranger.test", "stranger");
    test_double("ts-down.test", "refuse");
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client_at(
        "lan-stranger.test",
        vec![
            endpoint("lan-stranger.test", "lan", 1_500),
            endpoint("ts-down.test", "tailscale", 0),
        ],
        dir.path(),
    );
    wait_for(&client, "failed", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Failed)
    })
    .await;
    let status = client.direct_status().unwrap();
    let error = status.last_error.unwrap();
    assert!(
        error.contains("ts-down.test") && error.contains("refused"),
        "{error}"
    );
    // Not a needs-the-user error: it keeps retrying.
    assert!(status.retry_at_ms.is_some());
    assert!(
        status.endpoints[0]
            .last_error
            .as_deref()
            .unwrap()
            .contains("HOST KEY CHANGED")
    );
    client.shutdown();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_new_dial_order_applies_on_the_next_reconnect() {
    start_engine("ts-c.test", Mode::Real).await;
    start_engine("lan-c.test", Mode::Real).await;
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client_at(
        "ts-c.test",
        vec![
            endpoint("ts-c.test", "tailscale", 0),
            endpoint("lan-c.test", "lan", 0),
        ],
        dir.path(),
    );
    wait_for(&client, "live over tailscale", |c| {
        active_kind(c).as_deref() == Some("tailscale")
            && c.direct_status()
                .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    // Home Wi-Fi: LAN first. The link in use stays until asked to move.
    client.set_direct_endpoints(vec![
        endpoint("lan-c.test", "lan", 1_500),
        endpoint("ts-c.test", "tailscale", 0),
    ]);
    let status = client.direct_status().unwrap();
    assert_eq!(
        status
            .endpoints
            .iter()
            .map(|e| e.kind.as_str())
            .collect::<Vec<_>>(),
        ["lan", "tailscale"]
    );
    assert!(
        status.endpoints[1].active && status.endpoints[1].last_ok_ms.is_some(),
        "stats follow the address"
    );
    client.reconnect_direct();
    wait_for(&client, "live over lan", |c| {
        active_kind(c).as_deref() == Some("lan")
            && c.direct_status()
                .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    client.shutdown();
}

#[test]
fn the_most_telling_error_wins() {
    let connect = |h: &str| SshError::Connect(format!("can't reach {h}"));
    let mismatch = SshError::HostKeyMismatch {
        expected: "SHA256:a".into(),
        actual: "SHA256:b".into(),
        algorithm: "ssh-ed25519".into(),
    };
    // Wrong password / key: every address shares it.
    assert!(matches!(
        SshError::most_telling(vec![(0, connect("lan")), (1, SshError::Auth("no".into()))]),
        Some(SshError::Auth(_))
    ));
    // A mismatch only when every address says so.
    assert_eq!(
        SshError::most_telling(vec![(0, mismatch.clone()), (1, connect("ts"))]),
        Some(connect("ts"))
    );
    assert_eq!(
        SshError::most_telling(vec![(0, mismatch.clone())]),
        Some(mismatch)
    );
    // Same kind: the earlier address.
    assert_eq!(
        SshError::most_telling(vec![(1, connect("ts")), (0, connect("lan"))]),
        Some(connect("lan"))
    );
    assert_eq!(SshError::most_telling(Vec::new()), None);
}

// ── host catalogs (New Session model list) ─────────────────────────────────

async fn live_client(
    host: &str,
    mode: Mode,
    dir: &std::path::Path,
) -> (
    Client,
    Arc<std::sync::Mutex<Vec<serde_json::Value>>>,
    String,
) {
    let seen = start_engine(host, mode).await;
    let client = direct_client(host, dir);
    wait_for(&client, "live", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    let device = client
        .direct_status()
        .and_then(|s| s.engine_device_id)
        .expect("engine device");
    // The link reads every offered CLI's models on its own right after
    // connecting; let that finish so each test sees only its own reads.
    wait_for_prefetch(&seen).await;
    lock(&seen).clear();
    (client, seen, device)
}

const OFFERED: [&str; 5] = ["claude-code", "codex", "devin", "pi", "opencode"];

async fn wait_for_prefetch(seen: &std::sync::Mutex<Vec<serde_json::Value>>) {
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        let read = lock(seen)
            .iter()
            .filter(|p| p.get("harness").is_some())
            .count();
        if read >= OFFERED.len() {
            return;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "catalog prefetch: {:?}",
            lock(seen)
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn log_has(client: &Client, needle: &str) -> bool {
    client
        .direct_status()
        .is_some_and(|s| s.log.iter().any(|l| l.message.contains(needle)))
}

/// The real engine's reply reaches the phone intact: gpt-6.1-sol on top,
/// marked live, and a plain read doesn't ask the engine to re-probe.
#[tokio::test(flavor = "multi_thread")]
async fn real_model_catalog_is_live_with_custom_model_on_top() {
    let dir = tempfile::tempdir().unwrap();
    let (client, seen, device) = live_client("catalog-real.test", Mode::Real, dir.path()).await;
    let harnesses = client.list_harnesses(&device).await;
    let offered: Vec<&str> = harnesses
        .iter()
        .filter(|h| h.offered())
        .map(|h| h.id.as_str())
        .collect();
    assert_eq!(offered, OFFERED);
    let catalog = client.model_catalog(&device, "codex", false).await;
    assert_eq!(catalog.source, crate::catalog::CatalogSource::Live);
    assert_eq!(catalog.error, None);
    assert_eq!(catalog.models.len(), 7);
    assert_eq!(catalog.models[0].id, "gpt-6.1-sol");
    // A plain read right after connecting is the prefetched live list.
    assert!(lock(&seen).is_empty(), "{:?}", lock(&seen));
    // `force` rides along only when asked for.
    client.model_catalog(&device, "codex", true).await;
    assert_eq!(
        lock(&seen).last().unwrap(),
        &serde_json::json!({"harness": "codex", "force": true})
    );
    client.shutdown();
}

/// One row a newer engine shapes differently used to fail the whole reply
/// and silently show the saved list. Now that row is repaired or skipped
/// and the rest stays live; the skip is in the connection log.
#[tokio::test(flavor = "multi_thread")]
async fn drifted_model_rows_degrade_one_row_not_the_list() {
    let dir = tempfile::tempdir().unwrap();
    let (client, _, device) = live_client("catalog-drift.test", Mode::Drifted, dir.path()).await;
    let catalog = client.model_catalog(&device, "codex", false).await;
    assert_eq!(catalog.source, crate::catalog::CatalogSource::Live);
    let ids: Vec<&str> = catalog.models.iter().map(|m| m.id.as_str()).collect();
    assert_eq!(ids[0], "gpt-6.1-sol");
    assert!(!ids.contains(&"gpt-7-preview"), "{ids:?}");
    assert_eq!(ids.len(), 7);
    assert!(
        log_has(&client, "ListModels codex: "),
        "{:?}",
        client.direct_status()
    );
    client.shutdown();
}

/// A failed live read falls back (saved list after one good read, else
/// built-in), says so through `source`, and leaves a warning in the
/// connection log instead of failing silently.
#[tokio::test(flavor = "multi_thread")]
async fn failed_model_read_is_visible_and_falls_back() {
    let dir = tempfile::tempdir().unwrap();
    let (client, seen, device) =
        live_client("catalog-broken.test", Mode::BrokenModels, dir.path()).await;
    let catalog = client.model_catalog(&device, "codex", false).await;
    assert_eq!(catalog.source, crate::catalog::CatalogSource::Static);
    assert!(
        catalog
            .error
            .as_deref()
            .unwrap()
            .contains("model catalog unavailable")
    );
    assert_eq!(catalog.models, crate::catalog::fallback_models("codex"));
    assert_eq!(lock(&seen).len(), 1, "the request reached the engine");
    assert!(
        log_has(&client, "ListModels codex failed: "),
        "{:?}",
        client.direct_status()
    );
    // A list saved by an earlier good read is what shows next.
    let saved = crate::catalog::DiskCatalog::new(dir.path());
    let earlier: Vec<crate::catalog::ModelInfo> =
        serde_json::from_value(fixture("ListModels")).unwrap();
    saved.put_models(&device, "codex", &earlier[1..]);
    let catalog = client.model_catalog(&device, "codex", true).await;
    assert_eq!(catalog.source, crate::catalog::CatalogSource::Saved);
    assert_eq!(catalog.models[0].id, "gpt-6-astra");
    client.shutdown();
}

/// A file link in a message (relative path with an editor line ref) is read from the chat's workspace by relative path.
#[tokio::test(flavor = "multi_thread")]
async fn file_links_read_from_the_chats_workspace() {
    let dir = tempfile::tempdir().unwrap();
    let (client, seen, _) = live_client("file-link.test", Mode::Real, dir.path()).await;
    wait_for(&client, "chats", |c| !c.workspace().projects.is_empty()).await;
    let chat = client
        .workspace()
        .sessions
        .values()
        .next()
        .expect("a chat")
        .clone();
    let url = "docs/report.md:12";
    let file = client.read_file_link(&chat.id, url).await.expect("read");
    assert_eq!(file.text.as_deref(), Some("# Report\n"));
    assert_eq!(
        lock(&seen).last().unwrap(),
        &serde_json::json!({"chatId": chat.id, "path": "docs/report.md"})
    );
    let outside = client.read_file_link(&chat.id, "/etc/passwd").await;
    assert!(
        matches!(outside, Err(crate::ClientError::InvalidArgument(_))),
        "{outside:?}"
    );
    client.shutdown();
}

/// Over a direct link only the transcript on screen streams: opening (or
/// preloading) a session subscribes nothing, attaching its view does, and
/// leaving stops it within a beat.
#[tokio::test(flavor = "multi_thread")]
async fn only_the_transcript_on_screen_streams() {
    let dir = tempfile::tempdir().unwrap();
    let (client, seen, _) = live_client("mirror-onscreen.test", Mode::Real, dir.path()).await;
    wait_for(&client, "chats", |c| !c.workspace().sessions.is_empty()).await;
    let chat = client.workspace().sessions.keys().next().unwrap().clone();
    let watches = |key: &str| {
        lock(&seen)
            .iter()
            .filter(|v| v.get(key).is_some_and(|c| c == &serde_json::json!(chat)))
            .count()
    };
    client.preload_sessions();
    let handle = client.open_session(&chat).unwrap();
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(
        watches("watch"),
        0,
        "opened but not on screen: nothing streams"
    );
    handle.set_view_attached(true);
    wait_for(&client, "the transcript stream", |_| watches("watch") == 1).await;
    handle.set_view_attached(false);
    let deadline = std::time::Instant::now() + Duration::from_secs(12);
    while watches("unwatch") == 0 {
        assert!(
            std::time::Instant::now() < deadline,
            "the stream should stop after leaving"
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    client.shutdown();
}

/// ListHarnesses failing to reach the computer doesn't fan out into one
/// ListModels per CLI, each waiting out its own timeout: the model reads
/// right after answer at once with the saved/built-in list and the reason.
#[tokio::test(flavor = "multi_thread")]
async fn an_unreachable_catalog_does_not_cascade() {
    test_double("catalog-down.test", "refuse");
    let dir = tempfile::tempdir().unwrap();
    let client = direct_client("catalog-down.test", dir.path());
    let device = "pc".to_owned();
    let harnesses = client.list_harnesses(&device).await;
    assert!(!harnesses.is_empty(), "built-in harnesses");
    let began = std::time::Instant::now();
    for h in ["codex", "claude-code", "gemini"] {
        let catalog = client.model_catalog(&device, h, false).await;
        assert_ne!(catalog.source, crate::catalog::CatalogSource::Live);
        let error = catalog.error.unwrap_or_default();
        assert!(error.contains("ListHarnesses failed"), "{error}");
    }
    assert!(
        began.elapsed() < Duration::from_secs(2),
        "model reads waited {:?}",
        began.elapsed()
    );
    client.shutdown();
}

/// Right after the link is up, before any transcript streams, the phone reads
/// and saves the computer's CLI list and every offered CLI's models (plain
/// reads, not forced re-probes), so New Session doesn't wait on a busy link.
#[tokio::test(flavor = "multi_thread")]
async fn the_catalog_is_read_and_saved_right_after_connecting() {
    let dir = tempfile::tempdir().unwrap();
    let seen = start_engine("catalog-prefetch.test", Mode::Real).await;
    let client = direct_client("catalog-prefetch.test", dir.path());
    wait_for_prefetch(&seen).await;
    let reads: Vec<serde_json::Value> = lock(&seen).clone();
    let wanted: Vec<serde_json::Value> = OFFERED
        .iter()
        .map(|h| serde_json::json!({ "harness": h }))
        .collect();
    for w in &wanted {
        assert!(reads.contains(w), "{reads:?}");
    }
    assert!(reads.iter().all(|p| p.get("force").is_none()), "{reads:?}");
    wait_for(&client, "live", |c| {
        c.direct_status()
            .is_some_and(|s| s.phase == DirectPhase::Live)
    })
    .await;
    let device = client
        .direct_status()
        .and_then(|s| s.engine_device_id)
        .expect("engine device");
    let saved = crate::catalog::DiskCatalog::new(dir.path());
    assert!(saved.harnesses(&device).is_some(), "harness list saved");
    for h in OFFERED {
        assert!(saved.models(&device, h).is_some(), "{h} models saved");
    }
    client.shutdown();
}

/// Opening a chat asks for the newest rows first (`openingTail`): they show
/// before the complete transcript arrives, which then replaces them. A
/// session that already shows its full history never drops back to the
/// tail when it resubscribes.
#[tokio::test(flavor = "multi_thread")]
async fn the_newest_rows_show_first_then_the_whole_transcript() {
    let dir = tempfile::tempdir().unwrap();
    let (client, seen, _) = live_client("opening-tail.test", Mode::OpeningTail, dir.path()).await;
    wait_for(&client, "chats", |c| !c.workspace().sessions.is_empty()).await;
    let chat = client.workspace().sessions.keys().next().unwrap().clone();
    let handle = client.open_session(&chat).unwrap();
    handle.set_view_attached(true);
    let ids = |h: &crate::session::SessionHandle| -> Vec<String> {
        h.snapshot()
            .transcript_messages()
            .iter()
            .map(|m| m.id.clone())
            .collect()
    };
    let began = std::time::Instant::now();
    while ids(&handle).is_empty() {
        assert!(began.elapsed() < Duration::from_secs(10), "nothing shown");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(ids(&handle), ["m3"], "the tail shows first");
    assert!(handle.snapshot().hydrated);
    while ids(&handle).len() < 3 {
        assert!(
            began.elapsed() < Duration::from_secs(10),
            "{:?}",
            ids(&handle)
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(ids(&handle), ["m1", "m2", "m3"]);
    let watch = lock(&seen)
        .iter()
        .find(|v| v.get("watch").is_some())
        .cloned()
        .unwrap();
    assert_eq!(watch["openingTail"], true, "{watch}");

    // Leave and come back: the warm session keeps its full history; the
    // resubscription's tail frame doesn't replace it.
    handle.set_view_attached(false);
    let deadline = std::time::Instant::now() + Duration::from_secs(12);
    while !lock(&seen).iter().any(|v| v.get("unwatch").is_some()) {
        assert!(
            std::time::Instant::now() < deadline,
            "the stream should stop"
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    handle.set_view_attached(true);
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while lock(&seen)
        .iter()
        .filter(|v| v.get("watch").is_some())
        .count()
        < 2
    {
        assert!(std::time::Instant::now() < deadline, "resubscribed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert_eq!(ids(&handle), ["m1", "m2", "m3"]);
    client.shutdown();
}
