# Zeron for Android

An unofficial Android client for [Zeron](https://github.com/zeronsh/zeron), rebuilt screen by screen from the official iOS app.

*English | [简体中文](README.zh-CN.md)*

<p>
  <img src="docs/screenshots/android-parity/android-sessions.png" width="200" alt="Sessions list">
  <img src="docs/screenshots/android-parity/android-transcript.png" width="200" alt="Session with tool activity">
  <img src="docs/screenshots/android-parity/android-cjk-transcript.png" width="200" alt="Chinese transcript">
  <img src="docs/screenshots/android-parity/android-chip-model.png" width="200" alt="Model chip menu">
</p>

Zeron controls coding agents (Claude Code, Codex, Cursor and others) through a small engine running on your computer. Upstream ships a desktop app and an iOS app, but no Android app. This repository adds one.

The app is written in Jetpack Compose and links the same Rust mobile core as the iOS app, so layout and behavior follow iOS closely. It also adds Chinese font fallback and was tested with a Chinese Pinyin keyboard.

## Status

- Offline demo workspace: works.
- Direct connection to your own Zeron engine over SSH (no Cloudflare relay or cloud account needed): works since round5. The phone logs in to your computer's OpenSSH server and tunnels to the engine on `127.0.0.1:27654`; you confirm the host-key fingerprint on first connect. Windows setup (Simplified Chinese, PowerShell): [docs/ssh-direct.md](docs/ssh-direct.md). Not yet supported in this mode: attachments and the shared message queue (a message sent while the agent is busy steers the current turn instead); pins and sections are kept on the phone only.
- In-app updates from this repository's GitHub Releases: works since round5 (Settings → Check for Updates, plus a quiet daily check). No token needed. Releases from round5 on are signed with one stable key. If you installed round4, uninstall it once and install the latest APK manually; after that, updates install in place.

## Download

Get the APK from [Releases](https://github.com/villatothesea/zeron-android-app/releases).

## Build

```bash
scripts/android/build-apk.sh
```

See [apps/android/README.md](apps/android/README.md) for requirements. The Android code lives in `apps/android` and `crates/mobile`; SSH direct mode lives in `crates/client/src/direct`. The rest of the tree is a copy of the upstream source. Release steps: [scripts/android/release.md](scripts/android/release.md).

## Upstream

For the desktop app, the engine, and multi-device sync, see the upstream repository: <https://github.com/zeronsh/zeron>.

This project is not affiliated with the Zeron maintainers. It is released under the [MIT License](LICENSE), the same as upstream, and keeps the upstream copyright notice.
