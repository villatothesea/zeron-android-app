//! Direct mode: the phone talks to the user's own machine over SSH — no
//! edge, no account. The engine's loopback IPC (the same `EngineRpc` the
//! desktop UI uses) rides a `direct-tcpip` channel; [`host::DirectHost`]
//! mirrors its registry and transcript streams into the phone's local docs,
//! so the rest of the client (views, composer, commands) is unchanged.

pub(crate) mod host;
mod ssh;

pub use ssh::{ProbeResult, SshKeyPair, generate_ed25519, import_key, probe};

/// Default engine IPC port (`ZERON_IPC_PORT` on the machine overrides it).
pub const DEFAULT_ENGINE_PORT: u16 = 27654;

/// How to reach one machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SshTarget {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub auth: SshAuth,
    /// The engine's IPC port on the machine's loopback.
    pub engine_port: u16,
    /// Pinned host key (`SHA256:…`). `None` = not trusted yet: connecting
    /// fails with [`SshError::HostKeyUnknown`] so the UI can ask.
    pub host_key_fingerprint: Option<String>,
}

#[derive(Clone, PartialEq, Eq)]
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

impl std::fmt::Debug for SshAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SshAuth::Key { .. } => f.write_str("Key(<redacted>)"),
            SshAuth::Password { .. } => f.write_str("Password(<redacted>)"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SshError {
    /// First contact: the UI shows the fingerprint and asks to trust it.
    #[error("unknown host key {algorithm} {fingerprint}")]
    HostKeyUnknown {
        fingerprint: String,
        algorithm: String,
    },
    /// The pinned key changed — possible MITM (or a reinstalled machine).
    #[error("HOST KEY CHANGED: expected {expected}, got {algorithm} {actual}")]
    HostKeyMismatch {
        expected: String,
        actual: String,
        algorithm: String,
    },
    #[error("{0}")]
    Connect(String),
    #[error("{0}")]
    Auth(String),
    #[error("{0}")]
    Engine(String),
    #[error("{0}")]
    Key(String),
}

impl SshError {
    /// Retrying won't help until the user acts (trust / fix credentials).
    pub fn needs_user(&self) -> bool {
        matches!(
            self,
            SshError::HostKeyUnknown { .. }
                | SshError::HostKeyMismatch { .. }
                | SshError::Auth(_)
                | SshError::Key(_)
        )
    }
}
