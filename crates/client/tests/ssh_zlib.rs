//! The phone's SSH client asks for zlib and the tunnel still works: an
//! in-process russh server offering what Windows OpenSSH offers by default
//! ("none,zlib@openssh.com"), a direct-tcpip channel to a local engine whose
//! EngineInfo reply carries ~1 MB of transcript-like text, and a byte count
//! on the TCP path between them.

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
        tokio::spawn(async move {
            let mut engine = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            let mut stream = channel.into_stream();
            let _ = tokio::io::copy_bidirectional(&mut stream, &mut engine).await;
            let _ = engine.shutdown().await;
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

async fn run(offer: &'static [russh::compression::Name]) -> (u64, usize) {
    let engine = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let engine_port = engine.local_addr().unwrap().port();
    tokio::spawn(zeron_rpc::serve_ws_listener(engine, Arc::new(Engine)));

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
    tokio::spawn(async move {
        let mut server = Sshd { engine_port };
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
    let result = probe(&target).await.expect("probe over the tunnel");
    assert_eq!(result.engine_device_id, "pc");
    (down.load(Ordering::Relaxed), 1024 * 1024)
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
