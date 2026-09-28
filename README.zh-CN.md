# Zeron 安卓版

[Zeron](https://github.com/zeronsh/zeron) 的非官方安卓客户端，照着官方 iOS 版逐屏复刻。

*[English](README.md) | 简体中文*

<p>
  <img src="docs/screenshots/android-parity/android-sessions.png" width="200" alt="会话列表">
  <img src="docs/screenshots/android-parity/android-transcript.png" width="200" alt="带工具调用的会话">
  <img src="docs/screenshots/android-parity/android-cjk-transcript.png" width="200" alt="中文会话">
  <img src="docs/screenshots/android-parity/android-chip-model.png" width="200" alt="模型选择菜单">
</p>

Zeron 通过电脑上的一个小引擎来管理编码 agent（Claude Code、Codex、Cursor 等）。原仓库有桌面端和 iOS 版，没有安卓版，这个仓库补上了安卓版。

app 用 Jetpack Compose 编写，和 iOS 版链接同一个 Rust 移动端核心，所以排版和行为都尽量跟 iOS 一致。另外加了中文字体回退，并用中文拼音输入法测试过。

## 当前进度

- 离线演示工作区：可用。
- 通过 SSH 直连你自己的 Zeron 引擎（不需要 Cloudflare 中转）：开发中。
- 从本仓库的 GitHub Releases 在 app 内更新：开发中。

## 下载

在 [Releases](https://github.com/villatothesea/zeron-android-app/releases) 下载 APK。

## 构建

```bash
scripts/android/build-apk.sh
```

环境要求见 [apps/android/README.md](apps/android/README.md)。安卓相关代码在 `apps/android` 和 `crates/mobile`，其余部分是原仓库源码的副本。

## 原仓库

桌面端、引擎和多设备同步请看原仓库：<https://github.com/zeronsh/zeron>。

本项目与 Zeron 维护者无关。采用与原仓库相同的 [MIT 协议](LICENSE)，并保留原仓库的版权声明。
