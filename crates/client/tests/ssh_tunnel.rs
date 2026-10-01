//! The direct link through a real SSH tunnel (an in-process russh server
//! offering what Windows OpenSSH offers by default, "none,zlib@openssh.com",
//! forwarding direct-tcpip channels to an in-process engine):
//!
//! - the phone asks for zlib and it is used after auth;
//! - each on-screen transcript streams on its own channel, which closes as
//!   soon as the chat leaves the screen.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use russh::server::{Msg, Server as _, Session};
use russh::{Channel, ChannelId};
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use zeron_client::direct::{SshAuth, SshTarget, generate_ed25519, probe};
use zeron_rpc::{RpcError, RpcReply, RpcService};

struct Engine;

#[async_trait::async_trait]
impl RpcService for Engine {
    async fn handle(&self, method: &str, _: serde_json::Value) -> Result<RpcReply, RpcError> {
        assert_eq!(method, "EngineInfo");
        let mut text = String::new();
        let words = [
            "reasoning",
            "tool",
            "output",
            "the",
            "file",
            "crates/client/src",
            "error",
            "let",
        ];
        let mut i = 0u64;
        while text.len() < 1024 * 1024 {
            i = i
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            text.push_str(words[(i >> 60) as usize % words.len()]);
            text.push_str(&format!(" {:x} ", i >> 48));
        }
        Ok(RpcReply::Value(
            serde_json::json!({ "deviceId": "pc", "pad": text }),
        ))
    }
}

#[derive(Clone)]
struct Sshd {
    engine_port: u16,
    /// direct-tcpip channels opened / finished.
    opened: Arc<AtomicU64>,
    closed: Arc<AtomicU64>,
}

impl russh::server::Server for Sshd {
    type Handler = Self;
    fn new_client(&mut self, _: Option<std::net::SocketAddr>) -> Self {
        self.clone()
    }
}

impl russh::server::Handler for Sshd {
    type Error = russh::Error;

    async fn auth_publickey(
        &mut self,
        _: &str,
        _: &russh::keys::ssh_key::PublicKey,
    ) -> Result<russh::server::Auth, Self::Error> {
        Ok(russh::server::Auth::Accept)
    }

    async fn channel_open_direct_tcpip(
        &mut self,
        channel: Channel<Msg>,
        _host: &str,
        _port: u32,
        _oa: &str,
        _op: u32,
        reply: russh::server::ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        reply.accept().await;
        let port = self.engine_port;
        self.opened.fetch_add(1, Ordering::SeqCst);
        let closed = self.closed.clone();
        tokio::spawn(async move {
            let mut engine = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            let mut stream = channel.into_stream();
            let _ = tokio::io::copy_bidirectional(&mut stream, &mut engine).await;
            let _ = engine.shutdown().await;
            closed.fetch_add(1, Ordering::SeqCst);
        });
        Ok(())
    }

    async fn channel_close(&mut self, _: ChannelId, _: &mut Session) -> Result<(), Self::Error> {
        Ok(())
    }
}

/// Count bytes server → phone on the wire.
async fn counting_proxy(to: u16, down: Arc<AtomicU64>) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (client, _) = listener.accept().await.unwrap();
            let server = TcpStream::connect(("127.0.0.1", to)).await.unwrap();
            let (mut cr, mut cw) = client.into_split();
            let (mut sr, mut sw) = server.into_split();
            tokio::spawn(async move {
                let _ = tokio::io::copy(&mut cr, &mut sw).await;
            });
            let down = down.clone();
            tokio::spawn(async move {
                let mut buf = vec![0u8; 64 * 1024];
                loop {
                    use tokio::io::AsyncReadExt;
                    let n = match sr.read(&mut buf).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => n,
                    };
                    down.fetch_add(n as u64, Ordering::Relaxed);
                    if cw.write_all(&buf[..n]).await.is_err() {
                        return;
                    }
                }
            });
        }
    });
    port
}

struct Machine {
    target: SshTarget,
    down: Arc<AtomicU64>,
    opened: Arc<AtomicU64>,
    closed: Arc<AtomicU64>,
}

/// An engine behind an SSH server, and how the phone reaches it.
async fn machine(
    engine: Arc<dyn RpcService>,
    offer: &'static [russh::compression::Name],
) -> Machine {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let engine_port = listener.local_addr().unwrap().port();
    tokio::spawn(zeron_rpc::serve_ws_listener(listener, engine));

    let host_key = russh::keys::PrivateKey::from(
        russh::keys::ssh_key::private::Ed25519Keypair::from_seed(&[9u8; 32]),
    );
    let fingerprint = host_key
        .public_key()
        .fingerprint(russh::keys::HashAlg::Sha256)
        .to_string();
    let config = Arc::new(russh::server::Config {
        keys: vec![host_key],
        auth_rejection_time: std::time::Duration::from_millis(1),
        preferred: russh::Preferred {
            compression: std::borrow::Cow::Borrowed(offer),
            ..russh::Preferred::DEFAULT
        },
        ..Default::default()
    });
    let sshd = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let sshd_port = sshd.local_addr().unwrap().port();
    let opened = Arc::new(AtomicU64::new(0));
    let closed = Arc::new(AtomicU64::new(0));
    let mut server = Sshd {
        engine_port,
        opened: opened.clone(),
        closed: closed.clone(),
    };
    tokio::spawn(async move {
        let _ = server.run_on_socket(config, &sshd).await;
    });
    let down = Arc::new(AtomicU64::new(0));
    let port = counting_proxy(sshd_port, down.clone()).await;

    let key = generate_ed25519("test").unwrap();
    let target = SshTarget {
        host: "127.0.0.1".into(),
        port,
        user: "villa".into(),
        auth: SshAuth::Key {
            private_key: key.private_openssh,
            passphrase: None,
        },
        engine_port,
        host_key_fingerprint: Some(fingerprint),
        endpoints: Vec::new(),
    };
    Machine {
        target,
        down,
        opened,
        closed,
    }
}

async fn run(offer: &'static [russh::compression::Name]) -> (u64, usize) {
    let m = machine(Arc::new(Engine), offer).await;
    let result = probe(&m.target).await.expect("probe over the tunnel");
    assert_eq!(result.engine_device_id, "pc");
    (m.down.load(Ordering::Relaxed), 1024 * 1024)
}

#[tokio::test(flavor = "multi_thread")]
async fn the_tunnel_is_compressed_when_the_server_offers_zlib() {
    // What Windows OpenSSH offers with its default `Compression delayed`.
    let (zlib, payload) = run(&[russh::compression::NONE, russh::compression::ZLIB_LEGACY]).await;
    // A server with compression off.
    let (plain, _) = run(&[russh::compression::NONE]).await;
    println!(
        "server → phone for a ~{} KB reply: zlib {} KB, none {} KB",
        payload / 1024,
        zlib / 1024,
        plain / 1024
    );
    assert!(
        plain as usize > payload,
        "uncompressed carries the whole reply"
    );
    assert!(
        zlib * 2 < plain,
        "zlib@openssh.com is negotiated and used after auth"
    );
}

// ── transcripts on their own channels ─────────────────────────────────────

fn fixture(name: &str) -> serde_json::Value {
    let text = match name {
        "EngineInfo" => include_str!("../src/direct/fixtures/EngineInfo.json"),
        "WatchDevices" => include_str!("../src/direct/fixtures/WatchDevices.json"),
        "WatchSpaces" => include_str!("../src/direct/fixtures/WatchSpaces.json"),
        "WatchChats" => include_str!("../src/direct/fixtures/WatchChats.json"),
        "WatchSessions" => include_str!("../src/direct/fixtures/WatchSessions.json"),
        "ListHarnesses" => include_str!("../src/direct/fixtures/ListHarnesses.json"),
        "ListModels" => include_str!("../src/direct/fixtures/ListModels.codex.json"),
        other => panic!("no fixture {other}"),
    };
    serde_json::from_str(text).unwrap()
}

fn entry(id: &str) -> serde_json::Value {
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

/// A 0.2.101-shaped engine: registry snapshots, and `WatchDocMessages`
/// with `openingTail` (tail, then the complete reset, then silence).
/// Records every transcript watch and when its stream ends.
struct Workspace(Arc<std::sync::Mutex<Vec<String>>>);

#[async_trait::async_trait]
impl RpcService for Workspace {
    async fn handle(&self, method: &str, params: serde_json::Value) -> Result<RpcReply, RpcError> {
        use futures::StreamExt;
        match method {
            "EngineInfo" | "ListHarnesses" | "ListModels" => Ok(RpcReply::Value(fixture(method))),
            "WatchDevices" | "WatchSpaces" | "WatchChats" | "WatchSessions" => {
                Ok(RpcReply::Stream(
                    futures::stream::iter([fixture(method)])
                        .chain(futures::stream::pending())
                        .boxed(),
                ))
            }
            "WatchDocMessages" => {
                let chat = params["chatId"].as_str().unwrap_or_default().to_owned();
                self.0.lock().unwrap().push(format!("watch {chat}"));
                struct Unwatch(Arc<std::sync::Mutex<Vec<String>>>, String);
                impl Drop for Unwatch {
                    fn drop(&mut self) {
                        self.0.lock().unwrap().push(format!("unwatch {}", self.1));
                    }
                }
                let guard = Unwatch(self.0.clone(), chat);
                let frames = [
                    serde_json::json!({ "reset": [entry("m3")], "historyPending": true }),
                    serde_json::json!({ "reset": [entry("m1"), entry("m2"), entry("m3")] }),
                ];
                Ok(RpcReply::Stream(
                    futures::stream::iter(frames)
                        .chain(futures::stream::pending())
                        .map(move |v| {
                            let _ = &guard;
                            v
                        })
                        .boxed(),
                ))
            }
            _ => Ok(RpcReply::Value(serde_json::json!({}))),
        }
    }
}

async fn eventually(what: &str, secs: u64, ok: impl Fn() -> bool) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(secs);
    while !ok() {
        assert!(std::time::Instant::now() < deadline, "timed out: {what}");
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn each_open_transcript_has_its_own_channel_closed_on_leaving() {
    let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
    let m = machine(
        Arc::new(Workspace(seen.clone())),
        &[russh::compression::NONE, russh::compression::ZLIB_LEGACY],
    )
    .await;
    let dir = tempfile::tempdir().unwrap();
    let mut config = zeron_client::ClientConfig::new("https://edge.invalid", dir.path());
    config.device_id = "android-test".into();
    config.platform = "android".into();
    let client = zeron_client::Client::new(
        config,
        zeron_client::Credentials::Direct(m.target.clone()),
        Arc::new(zeron_client::events::NullListener),
    )
    .unwrap();
    eventually("live", 20, || {
        client
            .direct_status()
            .is_some_and(|s| s.phase == zeron_client::direct::DirectPhase::Live)
    })
    .await;
    // Feed, requests, and the spare for the first transcript.
    eventually("three channels at connect", 10, || {
        m.opened.load(Ordering::SeqCst) >= 3
    })
    .await;
    let chats: Vec<String> = client
        .workspace()
        .sessions
        .keys()
        .take(2)
        .cloned()
        .collect();
    let ids = |h: &zeron_client::SessionHandle| -> Vec<String> {
        h.snapshot()
            .transcript_messages()
            .iter()
            .map(|m| m.id.clone())
            .collect()
    };

    let a = client.open_session(&chats[0]).unwrap();
    a.set_view_attached(true);
    eventually("chat A's rows", 10, || ids(&a) == ["m1", "m2", "m3"]).await;
    // A took the spare; a new spare is opened for the next chat.
    eventually("spare refilled", 10, || {
        m.opened.load(Ordering::SeqCst) >= 4
    })
    .await;
    let closed_before = m.closed.load(Ordering::SeqCst);

    // Leaving A closes its channel at once (not at the next 5 s beat).
    let left = std::time::Instant::now();
    a.set_view_attached(false);
    eventually("A's channel closed", 3, || {
        m.closed.load(Ordering::SeqCst) > closed_before
    })
    .await;
    eventually("A's stream ended", 3, || {
        seen.lock()
            .unwrap()
            .iter()
            .any(|e| e == &format!("unwatch {}", chats[0]))
    })
    .await;
    println!(
        "A's channel closed {} ms after leaving",
        left.elapsed().as_millis()
    );

    let b = client.open_session(&chats[1]).unwrap();
    b.set_view_attached(true);
    eventually("chat B's rows", 10, || ids(&b) == ["m1", "m2", "m3"]).await;
    assert_eq!(
        ids(&a),
        ["m1", "m2", "m3"],
        "A keeps what it showed after its channel closed"
    );
    println!(
        "channels opened {}, closed {}; engine saw {:?}",
        m.opened.load(Ordering::SeqCst),
        m.closed.load(Ordering::SeqCst),
        seen.lock().unwrap()
    );
    client.shutdown();
}
