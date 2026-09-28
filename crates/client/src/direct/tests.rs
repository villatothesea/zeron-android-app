//! Direct mode against an in-process engine that serves a sanitized capture
//! of a real 0.2.97 desktop engine in local mode (40 chats, 23 archived, 11
//! spaces, 13 sessions with running turns, Windows paths), plus version
//! drift and a silent engine.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use zeron_proto::Chat;
use zeron_rpc::{RpcError, RpcReply, RpcService};

use super::host::{TEST_ENGINES, decode_rows};
use super::{DirectPhase, SshAuth, SshTarget};
use crate::events::NullListener;
use crate::{Client, ClientConfig, Credentials, lock};

fn fixture(name: &str) -> serde_json::Value {
    let text = match name {
        "EngineInfo" => include_str!("fixtures/EngineInfo.json"),
        "WatchDevices" => include_str!("fixtures/WatchDevices.json"),
        "WatchSpaces" => include_str!("fixtures/WatchSpaces.json"),
        "WatchChats" => include_str!("fixtures/WatchChats.json"),
        "WatchSessions" => include_str!("fixtures/WatchSessions.json"),
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
    value
}

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Real,
    Drifted,
    /// Answers EngineInfo and every watch but WatchChats.
    SilentChats,
}

struct Engine(Mode);

#[async_trait::async_trait]
impl RpcService for Engine {
    async fn handle(&self, method: &str, _params: serde_json::Value) -> Result<RpcReply, RpcError> {
        if method == "EngineInfo" {
            return Ok(RpcReply::Value(fixture("EngineInfo")));
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

async fn start_engine(host: &str, mode: Mode) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", listener.local_addr().unwrap());
    crate::runtime::shared().spawn(zeron_rpc::serve_ws_listener(
        listener,
        Arc::new(Engine(mode)),
    ));
    lock(&TEST_ENGINES)
        .get_or_insert_with(Default::default)
        .insert(host.to_owned(), url);
}

fn direct_client(host: &str, dir: &std::path::Path) -> Client {
    let target = SshTarget {
        host: host.to_owned(),
        port: 22,
        user: "dev".into(),
        auth: SshAuth::Password {
            password: String::new(),
        },
        engine_port: 27654,
        host_key_fingerprint: Some("SHA256:test".into()),
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
            "WatchDevices" => decode_rows::<zeron_proto::Device>(value).unwrap().errors,
            "WatchSpaces" => decode_rows::<zeron_proto::Space>(value).unwrap().errors,
            "WatchChats" => decode_rows::<Chat>(value).unwrap().errors,
            _ => decode_rows::<zeron_proto::Session>(value).unwrap().errors,
        };
        assert!(errors.is_empty(), "{method}: {errors:?}");
        assert!(n > 0);
    }
}

#[test]
fn drifted_rows_are_skipped_not_fatal() {
    let decoded = decode_rows::<Chat>(drifted("WatchChats", fixture("WatchChats"))).unwrap();
    assert_eq!(decoded.rows.len(), 39);
    assert_eq!(decoded.errors.len(), 1, "{:?}", decoded.errors);
    assert_eq!(decoded.ids.len(), 40, "the broken row's id still guards it");
    // Wrapped lists and junk frames.
    let wrapped = serde_json::json!({ "chats": fixture("WatchChats"), "cursor": 3 });
    assert_eq!(decode_rows::<Chat>(wrapped).unwrap().rows.len(), 40);
    assert!(decode_rows::<Chat>(serde_json::json!("nope")).is_err());
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
    assert_eq!(status.engine_version.as_deref(), Some("0.2.97"));
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
    assert_eq!((chats.rows, chats.skipped_rows), (39, 1));
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
