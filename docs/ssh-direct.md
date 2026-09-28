# SSH 直连模式：手机直接连你的 Windows 电脑

不需要 Zeron 云账号，也不经过中继。手机通过 SSH 登录你的电脑，再经 SSH 隧道
（direct-tcpip）连到本机的 Zeron 引擎（`127.0.0.1:27654`）。引擎端口不对外开放，
只开放 SSH（22）。

```
手机 ──SSH(22)──▶ Windows sshd ──隧道──▶ 127.0.0.1:27654 (Zeron 引擎)
```

以下命令都在**管理员 PowerShell** 中执行（开始菜单右键 → 终端(管理员)）。

## 1. 安装并启动 OpenSSH Server

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service sshd -StartupType Automatic
```

检查防火墙规则（安装时通常会自动创建）：

```powershell
Get-NetFirewallRule -Name *OpenSSH-Server* | Select-Object Name, Enabled, Profile
```

如果没有输出，手动添加：

```powershell
New-NetFirewallRule -Name OpenSSH-Server-In-TCP -DisplayName "OpenSSH Server (sshd)" -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
```

## 2. 添加手机的公钥

在手机上打开 **设置 → 机器（Machine）→ 本机 SSH 公钥**，点"复制公钥"，发到电脑上
（微信文件传输助手、邮件等都可以）。整行形如 `ssh-ed25519 AAAA... zeron-xxx`。

管理员账户的公钥必须放在 `administrators_authorized_keys`（不是用户目录下的
`.ssh\authorized_keys`）：

```powershell
$key = 'ssh-ed25519 AAAA...把手机上复制的整行粘贴到这里...'
Add-Content -Path C:\ProgramData\ssh\administrators_authorized_keys -Value $key -Encoding ascii
icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant "Administrators:F" /grant "SYSTEM:F"
```

> 权限不对（例如继承了 Users 的读取权限）时，sshd 会静默忽略这个文件，手机会提示认证失败。

如果你的 Windows 账户**不是**管理员，改为：

```powershell
New-Item -ItemType Directory -Force $env:USERPROFILE\.ssh | Out-Null
Add-Content -Path $env:USERPROFILE\.ssh\authorized_keys -Value $key -Encoding ascii
```

也可以不用公钥，在手机上选择"密码"登录（使用 Windows 账户密码；微软账户登录的电脑用微软账户密码）。

## 3. 查电脑的局域网 IP

```powershell
ipconfig
```

找到正在使用的网卡（WLAN 或 以太网）下的 **IPv4 地址**，例如 `192.168.1.23`。
手机和电脑需在同一局域网（或通过 Tailscale/ZeroTier 等组网，填对应 IP）。

## 4. 让 Zeron 引擎保持运行

最简单：电脑上保持 Zeron 应用打开。

或者登录时自动以无界面模式启动引擎。Zeron 是免安装的单个 exe，先确定它的路径
（Zeron 正在运行时可以直接查）：

```powershell
(Get-Process zeron).Path
```

把输出的路径填进 `$exe`，然后创建登录任务：

```powershell
$exe = "C:\Tools\Zeron\zeron.exe"
schtasks /Create /TN "Zeron Headless" /SC ONLOGON /RL LIMITED /TR "\"$exe\" headless"
schtasks /Run /TN "Zeron Headless"
```

## 5. 验证

引擎在监听 27654：

```powershell
netstat -ano | findstr 27654
```

应看到 `127.0.0.1:27654 ... LISTENING`。

sshd 在监听 22：

```powershell
netstat -ano | findstr ":22 "
```

## 6. 核对主机指纹（首次连接时）

```powershell
ssh-keygen -lf C:\ProgramData\ssh\ssh_host_ed25519_key.pub
```

输出形如 `256 SHA256:xxxxxxxx... (ED25519)`。手机第一次连接时会弹出
"Trust this machine?"，显示 `SHA256:...` 指纹，**两者一致再点 Trust**。
之后如果指纹变化，手机会拒绝连接并提示"Host key changed"（可能是重装系统，
也可能是中间人攻击）。

## 7. 在手机上添加机器

设置 → 机器 → 右上角 **+**：

| 字段 | 填写 |
| --- | --- |
| Name | 随意，例如 `家里的电脑` |
| Host | 第 3 步的 IPv4 地址 |
| SSH port | `22` |
| Zeron port | `27654`（默认） |
| User | Windows 用户名（`whoami` 输出中 `\` 后面的部分） |
| Sign in with | This phone's key（推荐）/ Import key / Password |

点 **Test** → 核对指纹 → **Trust** → 看到"Connected · Zeron 0.2.x answered in … ms" → **Save & Connect**。

## 常见问题

- **Connect 失败 / 超时**：确认 IP、同一网络、防火墙 22 端口放行；`Get-Service sshd` 状态为 Running。
- **Auth 失败**：公钥文件路径和 `icacls` 权限；用户名是否正确。可在电脑上看日志：
  `Get-WinEvent -LogName OpenSSH/Operational -MaxEvents 20 | Format-List TimeCreated, Message`
- **SSH 成功但引擎连不上**：Zeron 没在运行（第 4、5 步）。
- **sshd 禁用了端口转发**：检查 `C:\ProgramData\ssh\sshd_config` 中没有 `AllowTcpForwarding no`；修改后 `Restart-Service sshd`。
- **连上了但会话列表是空的 / 一直在加载**：会话页顶部会显示连接状态横幅（连接中 / 正在加载会话 / 错误原因）。
  点 **Details**（或 设置 → Connection Details）查看引擎版本、每个数据流（WatchDevices / WatchSpaces / WatchChats / WatchSessions）收到的帧数和行数，以及连接日志；
  点右上角 **Copy** 可把这份诊断文本（不含密钥）复制出来反馈。20 秒内有数据流一直没有数据时，会提示是哪一个并自动重连。
- **引擎版本比 App 新**：App 对多出来的字段和无法识别的行做宽松处理，只跳过读不了的那一行，并在横幅和 Details 里提示跳过了几行。

## 目前的限制

- 直连模式暂不支持发送附件（图片/文件）。
- 共享消息队列关闭：会话忙时发送的消息会作为"插话（steer）"发给当前轮次。
- 置顶和分组只保存在手机本地。
- 只显示引擎所在电脑的在线状态。
