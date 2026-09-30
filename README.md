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

## Connecting your phone to your computer

*完整的中文分步指南（含火绒设置、常见问题）：[README.zh-CN.md → 把手机连到电脑](README.zh-CN.md#把手机连到电脑)。*

The app connects to the **computer running Zeron desktop**. Keep Zeron open there; its engine listens on `127.0.0.1:27654`, which is never exposed: the phone reaches it through an SSH tunnel. Replace everything in `<angle brackets>` with your own values. Menu names below are the app's English UI (Settings → Language switches it).

### A. Same Wi-Fi / LAN (SSH)

1. **Enable OpenSSH Server.** Right-click **Start → Terminal (Admin)** (Windows 10: **Windows PowerShell (Admin)**) and run:
   ```powershell
   Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
   Start-Service sshd
   Set-Service sshd -StartupType Automatic
   Get-NetFirewallRule -Name *OpenSSH-Server* | Select-Object Name, Enabled, Profile
   ```
   If the last command prints nothing, add the rule:
   ```powershell
   New-NetFirewallRule -Name OpenSSH-Server-In-TCP -DisplayName "OpenSSH Server (sshd)" -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
   ```
2. **Set the network to Private**: **Settings → Network & internet → Wi-Fi** (or **Ethernet**) → your network → **Network profile type → Private network**. Third-party security software (e.g. Huorong / 火绒: **防护中心 → 网络防护**, check IP protocol control, app network control and brute-force protection) can block port 22 on its own; allow inbound TCP 22 or `sshd.exe`.
3. **Add the phone's key.** On the phone: profile icon (top right of the home screen) → **Settings → Accounts & Computers → This phone's SSH key → Copy public key**, and send the line to the PC. For an **administrator** account (most personal PCs) it must go in `administrators_authorized_keys`:
   ```powershell
   $key = 'ssh-ed25519 AAAA...paste the whole line here...'
   Add-Content -Path C:\ProgramData\ssh\administrators_authorized_keys -Value $key -Encoding ascii
   icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant "Administrators:F" /grant "SYSTEM:F"
   ```
   For a standard account, append it to `$env:USERPROFILE\.ssh\authorized_keys` instead.
4. **Find the IP and user name**: `ipconfig` (the **IPv4 Address** of the active adapter, `<PC-IP>`, e.g. `192.168.x.x`) and `$env:USERNAME` (`<Windows-user>`, not your Microsoft account email).
5. **Add the computer in the app**: **Settings → Accounts & Computers → Add a computer**. Host `<PC-IP>`, SSH port `22`, Zeron port `27654`, User `<Windows-user>`, Sign in with **This phone's key**. Tap **Test**, compare the fingerprint with `ssh-keygen -lf C:\ProgramData\ssh\ssh_host_ed25519_key.pub` on the PC, tap **Trust**, then **Save & Connect**.

### B. Remote (Tailscale)

Do A first, then:

1. Install [Tailscale](https://tailscale.com/download) on the PC and the phone and sign in to **the same account** on both.
2. Get the PC's Tailscale address: `tailscale ip -4` (a `100.x.y.z` address).
3. In the app, add another computer with Host `100.x.y.z`; everything else as in A5.

- **Android allows one VPN at a time.** Tailscale is a VPN, so turn off Clash, v2rayNG and similar proxy apps on the phone while you use it.
- **Clash TUN mode on the PC** also captures Tailscale traffic. Add this rule at the top of your rules:
  ```yaml
  rules:
    - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve
  ```

### C. Zeron Cloud account (not available yet)

The app has a **Zeron Cloud** entry under **Settings → Accounts & Computers → Other workspaces**, ported from the iOS app, for connecting through the official Zeron account and relay (edge). Support for the official edge relay isn't implemented or verified on Android yet, and this project runs no relay of its own, so signing in will most likely not reach your computer. Use A or B.

### Troubleshooting

- **Timeouts**: same Wi-Fi (guest networks and AP isolation block this)? IP changed (`ipconfig`)? `Get-Service sshd` is `Running` and `Test-NetConnection -ComputerName localhost -Port 22` succeeds? Network set to Private? Tailscale only: no other VPN on the phone, Clash rule above, and the Tailscale adapter not marked Public (`Set-NetConnectionProfile -InterfaceAlias "Tailscale" -NetworkCategory Private`).
- **sshd service missing after a Windows update** (`Get-Service sshd` finds nothing): check where `sshd.exe` is (`Test-Path 'C:\Program Files\OpenSSH\sshd.exe'`, `Test-Path 'C:\Windows\System32\OpenSSH\sshd.exe'`) and re-register it with the matching path:
  ```powershell
  New-Service -Name sshd -BinaryPathName '"C:\Program Files\OpenSSH\sshd.exe"' -StartupType Automatic -DisplayName 'OpenSSH SSH Server'
  Start-Service sshd
  ```
  Host keys and authorized keys live in `C:\ProgramData\ssh`, so the phone needs no changes. If neither path exists, rerun step A1.
- **Auth failed**: check the user name, the key file location and the `icacls` line; logs: `Get-WinEvent -LogName OpenSSH/Operational -MaxEvents 20 | Format-List TimeCreated, Message`.
- **SSH works but no sessions**: Zeron isn't running on the PC (`netstat -ano | findstr 27654` should show `LISTENING`). **Settings → Connection Details** shows the link state and log.
- **App update fails**: update checks and downloads use GitHub Releases, so the phone must reach `api.github.com` and `github.com`. A download mirror can be set under **Settings → Check for Updates → Advanced → Download mirror & GitHub token** (downloads only; the check itself still needs GitHub), or download the APK from [Releases](https://github.com/villatothesea/zeron-android-app/releases) on the PC.

More detail: [docs/ssh-direct.md](docs/ssh-direct.md) (Chinese).

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
