# Release Process

> DSHBox 发布流程 · APK 签名与分发说明

## 概述

DSHBox 使用 GitHub Actions 自动化发布流水线。每次推送 `v*` 标签触发完整构建链：

1. 构建 ARM64 Runtime Bundle（Debian rootfs + Node.js + DSH）
2. 将 Bundle 内置进 APK
3. 签名 Release APK
4. 创建 GitHub Release（附 APK + Bundle + SHA256SUMS）

---

## 触发发布

```bash
# 打标签并推送
git tag v0.1.0
git push origin v0.1.0
```

GitHub Actions 自动执行 `release.yml` 工作流。

---

## 手动构建（本地）

### 前提条件

- JDK 17+
- Android SDK 36（platforms + build-tools + platform-tools）
- Linux 环境（构建 Runtime Bundle 需要 `debootstrap` + `qemu-user-static`）

### 步骤

```bash
# 1. 构建 Runtime Bundle
tools/build_arm64_runtime_bundle.sh

# 2. 构建 APK（内置 Bundle）
tools/build_apk.sh build/dshapp-runtime-debian-arm64-rootfs.tar.gz release

# 产物：app/build/outputs/apk/release/app-release.apk
```

### 签名密钥

Release 构建需要签名密钥。本地开发环境：

```bash
# 生成开发密钥
tools/create_keystore.sh

# 产物：
#   ~/.android/dshapp-release.jks  — 密钥库
#   keystore.properties              — Gradle 签名配置
```

> ⚠️ 生产密钥必须安全离线备份，不要提交到 Git。

---

## CI/CD 发布流水线

### 配置密钥

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中设置：

| Secret | 说明 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | Release 密钥库的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |

生成 Base64 编码：

```bash
base64 -w0 keystore-release.jks | pbcopy
# Linux: base64 -w0 keystore-release.jks | xclip
```

### 工作流文件

| 文件 | 触发条件 | 作用 |
|------|---------|------|
| `.github/workflows/android.yml` | push/PR 到 main/develop | 单元测试、lint、覆盖率、debug APK 构建、安全扫描 |
| `.github/workflows/release.yml` | 推送 v* 标签 | 构建 Runtime Bundle → 签名 Release APK → 发布 GitHub Release |

### 发布产物

每次发布包含：

- `app-release.apk` — 签名后的 Release APK（约 345MB，内含完整运行环境）
- `dshapp-runtime-debian-*-rootfs.tar.gz` — 独立 Runtime Bundle（约 392MB）
- `SHA256SUMS` — 所有产物的 SHA-256 校验和

---

## 版本号规范

遵循 [SemVer](https://semver.org/)：

- `v0.1.0` — 初始版本
- `v0.1.1` — 补丁版本
- `v0.2.0` — 功能版本

版本号同时更新：
- `app/build.gradle.kts` 中的 `versionName`
- 标签名

---

## 更新日志

参见 [CHANGELOG.md](CHANGELOG.md)（如存在）。

---

## 验证

发布后验证：

1. 从 GitHub Release 下载 APK
2. 安装到 Android 设备
3. 确认首次启动正常解包运行环境
4. 确认 DSH WebUI 可访问 `http://127.0.0.1:3080`
5. 确认模型配置功能正常

```bash
# 本地验证 APK 签名
tools/verify_dist.sh
```