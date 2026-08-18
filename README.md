# DSHBox — DeepSeek Harness Mobile Agent Sandbox

[![Android CI](https://github.com/dshbox/DSHBox/actions/workflows/android.yml/badge.svg)](https://github.com/dshbox/DSHBox/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **DSHBox** 是在 Android 设备上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）的沙箱 App。  
> 装 APK 即用，无需 root，无需 adb。

---

## 语言 / Language

- [中文](#中文)
- [English](#english)

---

## 中文

### 概述

DSHBox = **Android 原生 UI** + **PRoot 用户态 Linux 沙箱** + **DSH Agent 运行时**。

DSH 作为 AI Agent 运行在沙箱内的 Debian 环境中，其 WebUI 通过系统浏览器访问（`http://127.0.0.1:3080`）。

```
┌──────────────────────────────────────────────┐
│            DSHBox (Android App)               │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
│  │ 首页状态  │ │ 文件管理  │ │ 沙箱终端/设置 │ │
│  └────┬─────┘ └────┬─────┘ └───────┬───────┘ │
│       └──────┬──────┴──────────────┘         │
│          SandboxService (前台保活)            │
│              │    SandboxManager              │
│              │    (PRoot 进程管理)             │
└──────────────┼───────────────────────────────┘
               │
    ┌──────────┴──────────┐
    │  PRoot 沙箱 (Debian) │
    │  ┌────────────────┐ │
    │  │  Node.js       │ │
    │  │  DSH Agent     │ │
    │  │  WebUI :3080   │ │
    │  └────────────────┘ │
    └─────────────────────┘
```

### 功能特性

- 🤖 **DSH AI Agent** — 模型对话、文件工具、bash 终端、子代理、目标管理
- 🗂️ **文件管理** — 沙盒文件 / 工作区双视图浏览，导入导出，目录 ZIP，搜索排序
- 🖥️ **沙箱终端** — App 内持久 bash shell 会话（含辅助键盘）
- 🔄 **运行环境管理** — 内置运行环境，支持更新导入、回滚、前台服务保活
- 🔒 **安全沙箱** — PRoot 用户态隔离，无需 root，沙箱与 Android 宿主相互独立
- 🌐 **支持多模型** — 可配置 DeepSeek / GLM-5.2 等多种 LLM Provider

### 截图

> ![首页](docs/screenshots/home.png) *首页 — DSH 状态与启动*
>
> ![文件管理](docs/screenshots/files.png) *文件管理 — 双视图浏览*
>
> ![终端](docs/screenshots/terminal.png) *沙箱终端 — bash 会话*
>
> ![设置](docs/screenshots/settings.png) *设置 — 模型配置与更新管理*

### 快速开始

1. 从 [Releases](https://github.com/dshbox/DSHBox/releases) 下载最新 APK 并安装。
2. 打开 App — 首次启动需 **1~3 分钟** 解包运行环境（约 400MB 内置）。
3. DSH 自动启动后，点击首页「打开 DSH」按钮，或在浏览器访问 `http://127.0.0.1:3080`。
4. 在 DSH WebUI 的模型设置中填入你的 **API Key**（支持 DeepSeek / GLM 等）。

> 提示：建议使用 Chrome 打开 DSH WebUI 并「添加到主屏幕」，体验更接近原生应用。

### 常见问题 (FAQ)

**Q: 需要 root 吗？**  
A: 不需要。DSHBox 使用 [PRoot](https://github.com/proot-me/PRoot) 在用户态创建 Linux 沙箱，无需 root 权限。

**Q: 首次启动为什么很慢？**  
A: 首次启动需要将内置的 Debian rootfs + Node.js + DSH 解压到 App 私有目录（约 400MB 数据），后续启动即瞬。

**Q: 如何配置模型？**  
A: 打开 App 设置页的「模型配置」，选择 Provider（DeepSeek / GLM-5.2 等），输入 API Key，保存后 App 自动写入沙箱配置并重启生效。也可在 DSH WebUI 内的模型设置中配置。

**Q: APK 有多大？为什么会这么大？**  
A: Release APK 约 345MB，因为内置了完整的 Debian ARM64 运行环境（rootfs + Node.js + DSH + 工具链）。Debug APK 约 5MB（不含运行环境，需通过 adb 手动安装运行环境包）。

**Q: 沙箱内的数据会丢失吗？**  
A: 沙箱用户数据存储在 `user-data/` 目录，独立于 rootfs。更新运行环境不会丢失用户数据。建议定期备份重要的 workspace 文件。

**Q: 如何更新运行环境？**  
A: 在设置页导入新的 `.tar.gz` 运行环境包，App 会自动校验 SHA-256 并执行双槽位切换（失败可回滚）。

**Q: 支持哪些 Android 版本？**  
A: 最低 Android 10（API 29）。推荐 Android 12+ 以获得最佳体验。

### 从源码构建

环境要求：JDK 17+、Android SDK 36；构建运行环境需要 Linux 环境（`debootstrap` + `qemu-user-static`）。

```bash
# 1. 构建运行环境 bundle（Debian arm64 rootfs + Node.js + DSH）
tools/build_arm64_runtime_bundle.sh

# 2. 构建 Release APK（bundle 内置进 APK）
tools/build_apk.sh build/dshapp-runtime-debian-arm64-rootfs.tar.gz release

# 产物：app/build/outputs/apk/release/app-release.apk
```

详细构建说明见 `docs/release-process.md`。

### 项目结构

```
DSHBox/
├── app/                    # 主应用模块 (Compose UI + Service)
├── bridge/                 # WebView JS 桥接模块 (安全模型)
├── common/                 # 公共基础模块 (常量/工具/结果类型)
├── sandbox-manager/        # 沙箱管理模块 (PRoot 进程/双槽位更新)
├── runtime-bundle/         # 运行环境构建 (Dockerfile + 脚本)
├── tools/                  # 构建/部署工具脚本
├── docs/                   # 文档
├── .github/workflows/      # CI/CD 流水线
└── gradle/                 # Gradle 版本目录
```

---

## English

### Overview

**DSHBox** is an Android sandbox app that runs [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (DSH) — an AI Agent runtime — inside a **PRoot userspace Linux sandbox**. No root access required.

The DSH Agent runs inside a Debian environment, exposing its WebUI at `http://127.0.0.1:3080` accessible via the system browser.

### Features

- 🤖 **DSH AI Agent** — Model chat, file tools, bash terminal, sub-agents, goal management
- 🗂️ **File Manager** — Dual-pane file browser, import/export, ZIP, search, sort
- 🖥️ **Sandbox Terminal** — Persistent bash shell sessions inside the app
- 🔄 **Runtime Management** — Built-in runtime, update import, rollback, foreground service
- 🔒 **Secure Sandbox** — PRoot user-space isolation, no root needed
- 🌐 **Multi-Model** — Configure DeepSeek, GLM-5.2, and other LLM providers

### Screenshots

> ![Home](docs/screenshots/home.png) *Home — DSH status & launch*
>
> ![Files](docs/screenshots/files.png) *File Manager — dual-pane browsing*
>
> ![Terminal](docs/screenshots/terminal.png) *Sandbox Terminal — bash session*
>
> ![Settings](docs/screenshots/settings.png) *Settings — model config & updates*

### Quick Start

1. Download and install the latest APK from [Releases](https://github.com/dshbox/DSHBox/releases).
2. Open the app — first boot takes **1–3 minutes** to extract the runtime (~400MB).
3. Tap "Open DSH" or visit `http://127.0.0.1:3080` in your browser.
4. Configure your **API Key** in the DSH WebUI settings.

> Tip: Use Chrome and "Add to Home Screen" for a native-like experience.

### FAQ

**Q: Does this require root?**  
A: No. DSHBox uses [PRoot](https://github.com/proot-me/PRoot) for userspace sandboxing — no root needed.

**Q: Why is first boot slow?**  
A: The app extracts a Debian rootfs + Node.js + DSH (~400MB) on first run. Subsequent starts are fast.

**Q: How do I configure models?**  
A: Open the app's Settings → Model Config, choose a provider (DeepSeek / GLM-5.2), enter your API key, and save — the app writes the sandbox config and restarts to activate. You can also configure models inside the DSH WebUI.

**Q: How large is the APK?**  
A: Release APK is ~345MB (includes a complete Debian ARM64 runtime). Debug APK is ~5MB (no runtime bundled; the runtime must be installed via adb).

**Q: Will updates lose my data?**  
A: User data is stored in `user-data/`, separate from the runtime. Updates preserve your data and support rollback.

**Q: What Android versions are supported?**  
A: Minimum Android 10 (API 29). Android 12+ recommended.

### Build from Source

Requirements: JDK 17+, Android SDK 36; runtime bundle build requires Linux (`debootstrap` + `qemu-user-static`).

```bash
# 1. Build runtime bundle (Debian arm64 rootfs + Node.js + DSH)
tools/build_arm64_runtime_bundle.sh

# 2. Build Release APK (bundle embedded)
tools/build_apk.sh build/dshapp-runtime-debian-arm64-rootfs.tar.gz release

# Output: app/build/outputs/apk/release/app-release.apk
```

See `docs/release-process.md` for detailed build & release instructions.

### Repository Structure

```
DSHBox/
├── app/                    # Main app module (Compose UI + Service)
├── bridge/                 # WebView JS bridge (security model)
├── common/                 # Shared module (constants/utils/results)
├── sandbox-manager/        # Sandbox management (PRoot / dual-slot runtime)
├── runtime-bundle/         # Runtime build (Dockerfile + scripts)
├── tools/                  # Build & deploy scripts
├── docs/                   # Documentation
├── .github/workflows/      # CI/CD pipelines
└── gradle/                 # Gradle version catalog
```

## License

[Apache License 2.0](LICENSE). Third-party component notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

> **DSHBox** — 让 DSH Agent 随时随地运行在您的 Android 设备上。  
> *Take DSH Agent with you, wherever you go.*