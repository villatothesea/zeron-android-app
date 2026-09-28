//! Direct mode (SSH to the user's own machine): targets, key helpers and
//! the pre-connect status probe.

use zeron_client as zc;
use zeron_client::direct as zd;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SshAuth {
    /// OpenSSH/PEM private key text (+ passphrase if encrypted).
    Key {
        private_key: String,
        passphrase: Option<String>,
    },
    Password {
        password: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SshTarget {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub auth: SshAuth,
    /// The engine's IPC port on the machine's loopback (default 27654).
    pub engine_port: u16,
    /// Pinned host key (`SHA256:…`); `None` until the user trusts it.
    pub host_key_fingerprint: Option<String>,
}

impl From<SshTarget> for zd::SshTarget {
    fn from(t: SshTarget) -> Self {
        zd::SshTarget {
            host: t.host,
            port: t.port,
            user: t.user,
            auth: match t.auth {
                SshAuth::Key {
                    private_key,
                    passphrase,
                } => zd::SshAuth::Key {
                    private_key,
                    passphrase,
                },
                SshAuth::Password { password } => zd::SshAuth::Password { password },
            },
            engine_port: t.engine_port,
            host_key_fingerprint: t.host_key_fingerprint,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum SshError {
    /// First contact: show the fingerprint and ask to trust it.
    #[error("unknown host key {algorithm} {fingerprint}")]
    HostKeyUnknown {
        fingerprint: String,
        algorithm: String,
    },
    /// The pinned key changed.
    #[error("host key changed: expected {expected}, got {actual}")]
    HostKeyMismatch {
        expected: String,
        actual: String,
        algorithm: String,
    },
    #[error("{reason}")]
    Connect { reason: String },
    #[error("{reason}")]
    Auth { reason: String },
    #[error("{reason}")]
    Engine { reason: String },
    #[error("{reason}")]
    Key { reason: String },
}

impl From<zd::SshError> for SshError {
    fn from(e: zd::SshError) -> Self {
        match e {
            zd::SshError::HostKeyUnknown {
                fingerprint,
                algorithm,
            } => SshError::HostKeyUnknown {
                fingerprint,
                algorithm,
            },
            zd::SshError::HostKeyMismatch {
                expected,
                actual,
                algorithm,
            } => SshError::HostKeyMismatch {
                expected,
                actual,
                algorithm,
            },
            zd::SshError::Connect(reason) => SshError::Connect { reason },
            zd::SshError::Auth(reason) => SshError::Auth { reason },
            zd::SshError::Engine(reason) => SshError::Engine { reason },
            zd::SshError::Key(reason) => SshError::Key { reason },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SshKeyPair {
    pub private_openssh: String,
    /// The authorized_keys line (`ssh-ed25519 AAAA… comment`).
    pub public_openssh: String,
    pub fingerprint: String,
}

impl From<zd::SshKeyPair> for SshKeyPair {
    fn from(k: zd::SshKeyPair) -> Self {
        Self {
            private_openssh: k.private_openssh,
            public_openssh: k.public_openssh,
            fingerprint: k.fingerprint,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProbeResult {
    pub host_key_fingerprint: String,
    pub host_key_algorithm: String,
    pub engine_device_id: String,
    pub latency_ms: u64,
}

/// New ed25519 key (the phone's identity for SSH).
#[uniffi::export]
pub fn ssh_generate_key(comment: String) -> Result<SshKeyPair, SshError> {
    Ok(zd::generate_ed25519(&comment)?.into())
}

/// Parse a pasted private key; returns it unencrypted plus its public half.
#[uniffi::export]
pub fn ssh_import_key(
    private_key: String,
    passphrase: Option<String>,
) -> Result<SshKeyPair, SshError> {
    Ok(zd::import_key(&private_key, passphrase.as_deref())?.into())
}

/// SSH auth + tunnel + `EngineInfo`. An unpinned target fails with
/// `HostKeyUnknown` carrying the fingerprint to confirm.
#[uniffi::export]
pub async fn ssh_probe(target: SshTarget) -> Result<ProbeResult, SshError> {
    let target: zd::SshTarget = target.into();
    let joined = zc::runtime::shared()
        .spawn(async move { zd::probe(&target).await })
        .await;
    match joined {
        Ok(Ok(p)) => Ok(ProbeResult {
            host_key_fingerprint: p.host_key_fingerprint,
            host_key_algorithm: p.host_key_algorithm,
            engine_device_id: p.engine_device_id,
            latency_ms: p.latency_ms,
        }),
        Ok(Err(e)) => Err(e.into()),
        Err(e) => Err(SshError::Connect {
            reason: format!("probe task failed: {e}"),
        }),
    }
}

/// Default engine IPC port.
#[uniffi::export]
pub fn ssh_default_engine_port() -> u16 {
    zd::DEFAULT_ENGINE_PORT
}
