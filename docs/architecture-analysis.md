# DSHBox 架构分析报告

> 任务 t1 产出 · glm-worker · 2026-08-17

## 一、项目架构概述

DSHBox 是在 Android 上运行 DeepSeek Harness（DSH）的沙箱 App。核心理念：**Android 原生 UI 管理沙箱生命周期，DSH WebUI 通过系统浏览器使用**。

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App (app)                       │
│  ┌─────────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ HomeScreen  │  │FilesScreen│  │SandboxScreen│ │Settings │ │
│  │ (启动/状态)  │  │(文件管理) │  │  (终端)    │  │ (配置)  │ │
│  └──────┬──────┘  └─────┬────┘  └─────┬─────┘  └────┬────┘ │
│         │               │              │              │      │
│  ┌──────┴───────────────┴──────────────┴──────────────┴────┐│
│  │              SandboxService (前台服务·保活)               ││
│  └──────────────────────────┬───────────────────────────────┘│
│                             │                                 │
│  ┌──────────────────────────┴───────────────────────────────┐│
│  │            ServiceLocator (手动 DI 容器)                   ││
│  │  ┌─────────────────┐  ┌──────────────────┐               ││
│  │  │ SandboxManager  │  │  BridgeRouter    │               ││
│  │  │ (沙箱生命周期)  │  │  (WebView 桥接)  │               ││
│  │  └────────┬────────┘  └────────┬─────────┘               ││
│  └───────────┼────────────────────┼─────────────────────────┘│
│              │                    │                            │
└──────────────┼────────────────────┼────────────────────────────┘
               │                    │
    ┌──────────┴──────┐    ┌───────┴──────────┐
    │ sandbox-manager  │    │     bridge       │
    │ ┌─────────────┐ │    │ ┌──────────────┐ │
    │ │BundleManager│ │    │ │OriginVerifier│ │
    │ │(双槽位更新) │ │    │ │BridgePolicy  │ │
    │ ├─────────────┤ │    │ │BridgeApi     │ │
    │ │ProcessRunner│ │    │ └──────────────┘ │
    │ │(PRoot进程)  │ │    └──────────────────┘
    │ ├─────────────┤ │
    │ │HealthChecker│ │         ┌─────────────────┐
    │ └─────────────┘ │         │  common          │
    └─────────────────┘         │ Constants/Result│
                                 │ LogRedactor     │
                                 │ DeviceProfile   │
                                 └─────────────────┘
```

### 模块依赖关系

```
app → common, bridge, sandbox-manager
bridge → common
sandbox-manager → common (+ commons-compress)
common → (无内部依赖, 仅 androidx-core-ktx + coroutines)
```

## 二、各模块职责详解

### 2.1 app（应用主模块）
**职责：** Android Application 入口、Compose UI 全栈、前台服务、DI 容器。

**关键组件：**
- `DshApp` — Application 子类，onCreate 时初始化 DI 容器 + 启动 SandboxService
- `MainActivity` — 单 Activity Compose 架构，`setContent { DshAppTheme { MainScreen() } }`
- `SandboxService` — 前台服务（FOREGROUND_SERVICE_TYPE_SPECIAL_USE），拥有沙箱生命周期；通知栏含"打开 DSH/重启/停止"三个操作
- `MainScreen` — 4 Tab 底部导航（首页/文件/沙盒/设置），品牌启动动画（鲸鱼轨道动画）
- `HomeScreen` — 状态卡片 + 地址卡片 + 打开/重启/停止按钮 + 运行时长
- `FilesScreen` — 沙盒文件/工作区双视图浏览（1364行，最大 UI 文件）
- `SandboxScreen` — 多会话终端入口 + 终端管理
- `TerminalScreen` — PRoot bash 终端模拟器，含辅助键盘（换行/空格/Tab/Esc/Ctrl/Alt/方向键/退格）
- `SettingsScreen` — DSH 状态/沙箱管理/外观/权限/更新/关于
- `DiagnosticsScreen` — 运行日志查看 + 导出
- `DevInstallReceiver` — Debug-only 广播接收器，adb 安装 Runtime Bundle
- `ServiceLocator` — 手动 DI，创建 SandboxConfig/DefaultSandboxManager/BridgeRouter（BridgeApi 全部 stub）
- `AppThemeState` — 主题模式持久化（跟随系统/浅色/深色）

### 2.2 bridge（WebView JS 桥接模块）
**职责：** DSH WebUI 与 Android 原生能力之间的安全通信桥梁。

**关键组件：**
- `BridgeApi` — 接口定义：Workspace/Filesystem/Command/Process/Android 原生能力
- `BridgeRouter` — JS 桥调用入口，能力授权/撤销管理
- `BridgePolicy` — 安全策略评估：信任级别 + 已授权能力 + 高风险需用户确认
- `OriginVerifier` — URL 分类：PUBLIC_WEB / LOCAL_WEB / TRUSTED_DSH_WEBUI
- `TrustLevel` — 三级信任枚举
- `BridgeModels` — CommandRequest/CommandResult/FileEntry/FileContent

**安全模型：**
- 非受信来源 → 全部拒绝
- 需 TRUSTED_DSH_WEBUI 级别（localhost + token 验证）
- 高风险能力（COMMAND/FILESYSTEM_WRITE/PROCESS）需用户显式授权
- 9 个能力枚举：WORKSPACE/FILESYSTEM_READ/FILESYSTEM_WRITE/COMMAND/PROCESS/ANDROID_NOTIFICATION/ANDROID_CLIPBOARD/ANDROID_FILE_PICKER/ANDROID_SHARE

### 2.3 common（公共基础模块）
**职责：** 跨模块共享的常量、结果类型、工具类。

**关键组件：**
- `Constants` — DSH 默认地址（127.0.0.1:3080）、目录名、超时常量、最大自动重启次数
- `AppResult/AppError` — 密封类型结果模型（Success/Failure + error code/message/cause/recoverable）
- `LogRedactor` — 日志脱敏（api_key/authorization/cookie/password/token/sk- 前缀）
- `DeviceProfile` — 设备性能分级（HIGH ≥12GB / STANDARD ≥8GB / LIGHT ≥4GB / UNSUPPORTED）

### 2.4 sandbox-manager（沙箱管理模块）
**职责：** PRoot 沙箱生命周期管理、Runtime Bundle 安装/更新/回滚、健康检查。

**关键组件：**
- `SandboxManager` — 接口：state StateFlow + initialize/start/stop/restart/forceStop/healthCheck/startDsh/stopDsh/recover/enterSafeMode + 运行时安装/切换/回滚
- `DefaultSandboxManager` — 状态机实现，含 Mutex 互斥、健康循环、有限自动重启、resolv.conf 修复
- `SandboxProcessRunner` — PRoot 进程启动 + 日志重定向 + 进程树 SIGKILL 清理
- `BundleManager` — tar.gz 解包（含 symlink/hardlink 安全检查）+ SHA-256 校验 + 双槽位管理
- `BundledRuntimeInstaller` — 首次启动从 APK assets 解包 Runtime
- `HttpHealthChecker` — 端口探活 + HTTP 探测
- `SandboxSupervisor` — 监控+恢复策略（最大重试次数后 ERROR）
- `SandboxConfig` — 配置数据类
- `SandboxState` — 8 态状态机：UNINITIALIZED→INITIALIZING→STARTING→RUNNING→READY / ERROR / RECOVERING / STOPPED

### 2.5 runtime-bundle（运行环境构建）
**职责：** Debian ARM64 rootfs + Node.js + DSH 的可复现构建、离线打包。

**关键组件：**
- `Dockerfile` — 基于 debian:trixie-slim，安装 Node 22.17.0 + pnpm 9.15.0 + DSH 0.1.0-rc.6 + patch_dsh_android.js
- `build_rootfs.sh` — 三种构建模式：debootstrap（qemu）/ docker / proot-distro
- `start_dsh.sh` — Debian 内启动 DSH WebUI（`node dsh/lib/bin.js web --host 127.0.0.1 --port 3080`）
- `patch_dsh_android.js` — 三处 Android 兼容性补丁（hardlink→rename fallback）
- `install_dsh.sh` — 精确版本安装 DSH
- `healthcheck.sh` — curl/wget 健康探测
- `bundle.yaml` — Bundle 清单（版本/架构/sha256/大小/组件）
- `init_sandbox.sh` / `start_sandbox.sh` — Android 侧入口脚本模板

### 2.6 tools（构建工具链）
**职责：** APK 构建、Runtime Bundle 打包、设备部署、CI 流水线。

**关键脚本：**
- `build_arm64_runtime_bundle.sh` — 构建完整 Runtime Bundle
- `build_apk.sh` — 将 Bundle 内置进 APK
- `deploy_to_device.sh` — adb 部署到设备
- `create_keystore.sh` — 签名密钥生成
- `pack_runtime.sh` / `pipeline_dryrun.sh` / `verify_dist.sh` — 打包/预检/校验
- `spawntest.js` — 进程 spawn 测试

## 三、PRoot 沙箱实现分析

### 3.1 PRoot 工作原理

DSHBox 使用 **PRoot**（用户态 Linux 沙箱）在无 root 的 Android 上运行完整 Debian rootfs：

1. **PRoot 二进制**（`libproot.so`）从 APK 的 jniLibs 加载，配合 `libproot-loader.so` 和 `libtalloc.so`
2. PRoot 通过 `ptrace` 拦截 guest 程序的系统调用，将文件路径重映射到 rootfs
3. 绑定挂载：`/system`、`/apex`、`/proc`、`/dev` + 用户数据目录 → `/root/projects`
4. 初始命令用宿主 `/system/bin/sh`（因为 untrusted_app 不能 exec app-data 下的 guest ELF），然后 `exec /usr/bin/bash` 进入 guest

### 3.2 进程启动命令

```kotlin
listOf(
    prootBinary,
    "--rootfs=$rootfsDir",
    "--bind=/system",
    "--bind=/apex",
    "--bind=/proc",
    "--bind=/dev",
    "--bind=$workspaceBind:/root/projects",
    "--cwd=/root",
    "--kill-on-exit",
    "/system/bin/sh", "-c",
    "exec /usr/bin/bash /opt/dshapp/start_dsh.sh",
)
```

环境变量：`LD_LIBRARY_PATH` → proot lib 目录，`PROOT_TMP_DIR` → runtime tmp，`PROOT_LOADER` → loader .so 路径。

### 3.3 进程清理策略

`SandboxProcessRunner.stop()` 的清理策略：
1. 读取 `/proc` 获取完整进程表
2. BFS 遍历子进程树找到 cmdline 含 `libproot.so` 的 PID
3. 先杀子进程（叶子优先），再杀 PRoot 根进程
4. 最后 `process.destroy()` 兜底

### 3.4 关键 Android 兼容性处理

- **resolv.conf 修复**：WSL 构建的 rootfs 带 `nameserver 10.255.255.254`（Android 不可达），每次启动检测并重写为 `114.114.114.114 / 8.8.8.8 / 223.5.5.5`
- **PATH 覆盖**：Android 继承宿主 PATH 导致 Node 找不到 bash，强制设 Debian PATH
- **hardlink fallback**：Android app-data 文件系统（FBE/FUSE）拒绝 hardlink，patch_dsh_android.js 将 `link()` → `rename()` fallback（3 处补丁）
- **DSH_PERMISSION_MODE=danger-full-access**：PRoot 内无 Landlock/bubblewrap，DSH 自带文件沙箱不可用，PRoot 本身就是边界
- **DSH_HOME=/root/projects/.dsh**：用户数据存持久化目录而非 rootfs（防止更新覆盖）

### 3.5 Runtime Bundle 管理（双槽位）

```
runtime/
├── runtime-current/    ← 当前运行的 rootfs + android-side
├── runtime-new/        ← 新版本安装槽
├── runtime-previous/   ← 回滚槽
└── runtime-failed/     ← 失败槽
```

流程：安装到 `runtime-new` → SHA-256 校验 → tar.gz 解包 → promote（current→previous, new→current）→ 回滚（current→failed, previous→current）

## 四、DSH 运行集成方式

### 4.1 启动链路

```
DshApp.onCreate()
  → ServiceLocator.createAppContainer()  // DI 初始化
  → SandboxService.start()               // 前台服务启动

SandboxService.onCreate()
  → startAsForeground()                  // 通知栏 + 前台服务
  → sandboxManager.state.collectLatest()  // 状态 → 通知更新
  → startSandbox():
      → sandboxManager.initialize()       // 创建目录
      → BundledRuntimeInstaller.installIfAbsent()  // 首次从 APK assets 解包
      → installFirstAvailableBundle()      // 或从 updates 目录安装
      → sandboxManager.start()             // 启动 PRoot
          → buildProotStartCommand()
          → processRunner.start()           // 启动 PRoot 进程
          → startHealthLoop()               // HTTP 健康循环
              → HttpHealthChecker.check()   // 端口+HTTP 探测
              → READY 状态 / 超时 ERROR
```

### 4.2 DSH WebUI 访问

DSH 监听 `127.0.0.1:3080`，用户通过**系统浏览器**（非 WebView）访问。App 提供：
- 首页"在浏览器中打开 DSH"按钮（`Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:3080")`）
- 通知栏"打开 DSH"操作
- NSC 配置允许 127.0.0.1/localhost cleartext traffic

### 4.3 健康检查

- 端口探活：Socket connect 127.0.0.1:3080 (2s timeout)
- HTTP 探测：GET http://127.0.0.1:3080/，200-299 视为 ready
- 循环间隔 2s，初始超时 120s
- ready 后掉线 → 最多 3 次自动重启，之后 ERROR

## 五、Android 原生 UI 与 DSH WebUI 的交互方式

### 5.1 当前模式

**分离式架构**：Android Compose UI 管理"沙箱生命周期"，DSH WebUI 在系统浏览器中使用。两者通过 `127.0.0.1:3080` 本机 HTTP 连接。

### 5.2 Bridge 模块（设计完成，实现待接入）

`bridge` 模块设计了完整的 WebView JS 桥安全模型，但当前 `ServiceLocator` 中 `BridgeApi` 是全 stub：

```kotlin
val noopBridge = object : BridgeApi { /* 全部返回空/默认值 */ }
val bridgeRouter = BridgeRouter(delegate = noopBridge, expectedDshToken = "")
```

**设计意图**：未来用 WebView 内嵌 DSH WebUI 时，通过 `BridgeRouter` 控制原生能力授权（文件/命令/进程/通知/剪贴板/文件选择器/分享）。

### 5.3 终端实现

`TerminalScreen` 独立启动 PRoot bash 进程（`createSandboxShell`），不经过 SandboxManager，直接在 UI 层管理进程生命周期。每个终端会话一个独立 PRoot 进程。

## 六、构建系统分析

### 6.1 Gradle KTS 多模块

- **Gradle 8.9.2** + **Kotlin 2.0.21** + **AGP**
- 版本统一管理：`gradle/libs.versions.toml`
- 4 个模块：app（application）+ common/bridge/sandbox-manager（library）
- compileSdk=36, minSdk=29, targetSdk=36, JDK 17
- release 构建启用 minify + shrinkResources + ProGuard
- jniLibs 内置 PRoot 原生库（arm64-v8a + x86_64）：libproot.so / libproot-loader.so / libtalloc.so / libandroid-shmem.so
- Compose BOM 2024.12.01，Material3
- 依赖：androidx core-ktx / activity-compose / compose-ui / material3 / material-icons / lifecycle / webkit / kotlinx-coroutines / commons-compress
- 无 Hilt/Dagger，手动 DI（ServiceLocator + AppContainer）
- 无 Room/DataStore，主题偏好用 SharedPreferences
- CI：GitHub Actions（test + lint + assembleDebug）

### 6.2 构建产物

- **Debug APK**：不内置 Runtime Bundle，需 adb 安装或 DevInstallReceiver
- **Release APK**：内置 Runtime Bundle（assets/runtime/*.dshb + .sha256），装 APK 即用（约 345MB）
- **Runtime Bundle**：独立 .tar.gz（约 392MB），含 Debian rootfs + Node + DSH

### 6.3 测试覆盖

5 个测试类，覆盖核心逻辑：
- `BundleManagerTest`（5 tests）— SHA-256/解包/模式保留/双槽位安装+回滚
- `SandboxProcessRunnerTest`（1 test）— PRoot 命令构造
- `SandboxStateTest`（2 tests）— 状态枚举
- `DeviceProfileTest`（1 test）— 性能分级
- `LogRedactorTest`（2 tests）— 日志脱敏

## 七、关键文件清单

| 文件 | 行数 | 作用 |
|---|---|---|
| `app/src/main/.../DshApp.kt` | 21 | Application 入口，DI + 服务启动 |
| `app/src/main/.../MainActivity.kt` | 20 | 单 Activity Compose 入口 |
| `app/src/main/.../service/SandboxService.kt` | 217 | 前台服务，沙箱生命周期 + 通知 |
| `app/src/main/.../di/ServiceLocator.kt` | 47 | 手动 DI 容器（BridgeApi 全 stub） |
| `app/src/main/.../ui/MainScreen.kt` | 213 | 底导航 + 启动动画 + Tab 管理 |
| `app/src/main/.../ui/home/HomeScreen.kt` | 373 | 首页：状态/地址/操作按钮 |
| `app/src/main/.../ui/files/FilesScreen.kt` | 1364 | 文件管理（最大 UI 文件） |
| `app/src/main/.../ui/sandbox/SandboxScreen.kt` | 198 | 终端管理 |
| `app/src/main/.../ui/sandbox/TerminalScreen.kt` | 425 | PRoot 终端 + 辅助键盘 |
| `app/src/main/.../ui/settings/SettingsScreen.kt` | 442 | 设置页 + 更新导入 |
| `app/src/main/.../dev/DevInstallReceiver.kt` | 96 | adb 安装 Runtime Bundle |
| `sandbox-manager/.../DefaultSandboxManager.kt` | 324 | 沙箱状态机 + 健康循环 + resolv.conf 修复 |
| `sandbox-manager/.../SandboxProcessRunner.kt` | ~170 | PRoot 进程启动 + 进程树清理 |
| `sandbox-manager/.../BundleManager.kt` | 250 | tar.gz 解包 + 双槽位管理 |
| `sandbox-manager/.../BundledRuntimeInstaller.kt` | 94 | 首次启动从 APK assets 解包 |
| `bridge/.../security/BridgePolicy.kt` | 45 | 安全策略评估 |
| `bridge/.../security/OriginVerifier.kt` | 31 | URL 信任分类 |
| `bridge/.../api/BridgeApi.kt` | 37 | JS 桥接口定义 |
| `runtime-bundle/scripts/start_dsh.sh` | 46 | DSH WebUI 启动脚本 |
| `runtime-bundle/scripts/patch_dsh_android.js` | 151 | 3 处 hardlink→rename 补丁 |
| `runtime-bundle/Dockerfile` | 55 | Debian ARM64 rootfs 构建 |

## 八、优化点（按优先级排序）

### P0 — 必须修复

**1. BridgeApi 全 stub，WebView 桥未实现**
- **现状**：`ServiceLocator` 中 `BridgeApi` 所有方法返回空值/默认值，`expectedDshToken = ""`
- **影响**：bridge 模块设计了完整安全模型但完全未接入；DSH WebUI 无法调用任何 Android 原生能力（文件导入导出、通知、剪贴板、分享等）
- **建议**：实现 `SandboxBridgeApi`（通过 PRoot 执行沙箱内命令/文件操作），接入 BridgeRouter 并生成 capability token；这是 GLM-5.2 接入的前置条件之一

**2. SandboxProcessRunner.kt 文件编码问题**
- **现状**：该文件被 read 工具识别为 binary（可能 BOM 或 mixed encoding），只能用 pwsh 读取
- **影响**：IDE/CI 可能遇到编码问题，代码搜索不工作
- **建议**：确认并统一为 UTF-8 无 BOM

**3. SandboxSupervisor 未被使用**
- **现状**：`SandboxSupervisor` 类已实现监控+恢复策略，但 `DefaultSandboxManager` 自己内联了 `startHealthLoop()`，未引用 Supervisor
- **影响**：代码重复，两套恢复逻辑不一致；Supervisor 的 `consecutiveFailures` 与 Manager 的 `restartAttempts` 独立
- **建议**：统一到一处，删除未使用的 Supervisor 或让 Manager 委托给它

### P1 — 高优先级

**4. TerminalScreen 直接创建 PRoot 进程，绕过 SandboxManager**
- **现状**：`createSandboxShell()` 在 UI 层直接构造 ProcessBuilder 启动 PRoot，路径和环境变量硬编码，与 `SandboxProcessRunner.buildProotStartCommand()` 重复
- **影响**：①路径逻辑重复（prootBinary/prootLoader 等选择逻辑两处维护）；②终端进程不受 SandboxManager 生命周期管理；③进程清理不一致（TerminalScreen 用 `process.destroy()`，SandboxProcessRunner 用 /proc 遍历 SIGKILL）
- **建议**：让 TerminalScreen 通过 SandboxManager 创建终端会话，复用 SandboxProcessRunner 的路径解析和进程清理

**5. DSH 版本固定为 0.1.0-rc.6，无版本管理和更新机制**
- **现状**：`install_dsh.sh` 和 `Dockerfile` 都硬编码 `DSH_VERSION=0.1.0-rc.6`，`start_dsh.sh` 路径硬编码 `/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js`
- **影响**：DSH 升级需重建整个 rootfs + Docker 镜像；无法在运行时切换 DSH 版本
- **建议**：将 DSH 版本写入 `bundle.yaml` 并在 start_dsh.sh 中从配置读取；考虑支持多版本 DSH 并存

**6. resolv.conf 检测逻辑脆弱**
- **现状**：`ensureGuestResolvConf()` 用字符串 contains 判断 resolv.conf 是否"broken"：检查是否含 `10.255.255.254`/`wsl`/不含 `114.114.114.114`/`8.8.8.8`/`223.5.5.5`
- **影响**：如果用户在 DSH 内手动改了 DNS 为其他公共 DNS（如 1.1.1.1），会被误判为 broken 并覆盖
- **建议**：改为只检测 WSL 标记和不可达地址，不强制特定 DNS；或在 runtime bundle 构建时就修复

**7. 没有错误上报和崩溃日志收集机制**
- **现状**：错误只通过 `android.util.Log` 输出到 logcat，DiagnosticsScreen 只显示 `process-proot.log` 最后 30 行
- **影响**：用户遇到问题无法导出完整诊断信息；开发者无法远程收集崩溃日志
- **建议**：增加结构化错误日志（JSON），收集 sandbox state transitions + DSH 进程 stderr + 健康检查历史，支持一键导出诊断包

### P2 — 中优先级

**8. FilesScreen 1364 行单文件过大**
- **现状**：文件管理页面所有逻辑（双视图、搜索、排序、导入导出、重命名、删除、ZIP）在一个文件中
- **影响**：可维护性差，难以测试，Compose 重组性能可能受影响
- **建议**：拆分为 FilesViewModel + 多个子 Composable（FileListSection / FileToolbar / ImportExportDialog / SearchBar）

**9. 无 ViewModel 架构，状态管理全在 Composable remember 中**
- **现状**：所有 UI 状态用 `remember`/`rememberSaveable`/`mutableStateOf` 在 Composable 内管理，无 ViewModel
- **影响**：①状态在配置变更时可能丢失（虽有 rememberSaveable）；②业务逻辑与 UI 耦合；③不可测试
- **建议**：引入 ViewModel（androidx.lifecycle.viewmodel.compose），至少为 SandboxService 交互和文件管理引入

**10. DeviceProfile 定义了性能分级但完全未使用**
- **现状**：`DeviceProfile` 和 `PerformanceTier` 枚举已定义并测试，但无任何代码读取设备信息或根据 tier 调整行为
- **影响**：设计好的性能自适应能力闲置
- **建议**：在 SandboxService 启动时构建 DeviceProfile，根据 tier 调整 DSH_READY_TIMEOUT_MS、health check 间隔、PRoot 绑定策略等

**11. CI 不构建 Runtime Bundle，Release APK 需手动构建**
- **现状**：GitHub Actions 只跑 test + lint + assembleDebug，不构建 runtime bundle 也不打 release APK
- **影响**：无自动化发布流水线
- **建议**：增加 release job（构建 rootfs → 打包 bundle → 内置进 APK → 签名 → 上传 Release）

**12. start_dsh.sh 的 npx fallback 不安全**
- **现状**：如果本地 DSH 安装不存在，fallback 到 `npx --yes @deepseek-ai/dsh web`
- **影响**：①需要网络；②可能拉到不兼容版本；③与"首次启动不依赖国际网络"的设计约束矛盾
- **建议**：移除 npx fallback，离线 bundle 必须保证 DSH 已安装；或改为报错而非 fallback

### P3 — 低优先级

**13. LogRedactor 正则覆盖不全**
- **现状**：6 个 pattern，未覆盖 URL 中的 token 参数（`?token=xxx`）、`X-API-Key` header、`set-cookie` 等
- **建议**：补充常见泄漏模式

**14. THIRD_PARTY_NOTICES 未提及 commons-compress**
- **现状**：sandbox-manager 依赖 `org.apache.commons:commons-compress`（Apache-2.0），但 THIRD_PARTY_NOTICES.md 未列入
- **建议**：补充声明

**15. 无 ProGuard 规则（consumer-rules.pro 全空）**
- **现状**：bridge/sandbox-manager/common 的 consumer-rules.pro 和 proguard-rules.pro 均为 0 字节
- **影响**：release 构建 minify 可能误删公共模块的反射使用（如 Os.symlink）
- **建议**：添加 keep 规则保护 commons-compress 和 Os 调用

## 九、GLM-5.2 接入切入点建议

### 9.1 最佳切入点：DSH settings.yaml 配置注入

DSHBox 的 DSH 运行在 PRoot Debian rootfs 内，DSH 自身的模型配置在 `~/.dsh/settings.yaml`（即 `/root/projects/.dsh/settings.yaml`，持久化在 user-data 目录）。

**接入方式：**
1. 在 `start_dsh.sh` 中检测 GLM-5.2 配置，或通过 App UI 预配置 settings.yaml
2. 在 SettingsScreen 增加"模型配置"section，允许用户输入 GLM API Key 和 base URL
3. 通过 BundleManager 或运行时写入 `user-data/.dsh/settings.yaml`

### 9.2 具体接入路径

```
用户在 SettingsScreen 输入 GLM-5.2 API Key
  → App 写入 user-data/.dsh/.credentials.yaml（KMS/DSH credentials 格式）
  → App 写入 user-data/.dsh/settings.yaml（provider + model 配置）
  → 重启沙箱 → DSH 读取配置 → GLM-5.2 可用
```

### 9.3 需要新增/修改的文件

| 文件 | 改动 |
|---|---|
| `app/.../ui/settings/SettingsScreen.kt` | 新增"模型配置"section（API Key 输入、Provider 选择、模型选择） |
| `app/.../ui/settings/ModelConfigScreen.kt` | **新增**：模型配置子页面 |
| `sandbox-manager/.../DshConfigWriter.kt` | **新增**：将用户配置写入 DSH settings.yaml/credentials.yaml |
| `runtime-bundle/scripts/start_dsh.sh` | 可能需要读取环境变量设置默认 provider |
| `app/src/main/res/values/strings.xml` | 新增模型配置相关字符串 |

### 9.4 GLM-5.2 配置格式参考

基于本机 DSH web profile 的 kabuai-glm 配置经验：
```yaml
# settings.yaml
providers:
  glm:
    baseURL: https://api.kabuai.cn/v1  # 或 open.bigmodel.cn
    apiKeyEnv: GLM_API_KEY
    models:
      - id: glm-5.2
        name: GLM-5.2
defaultModel: glm-5.2
```

### 9.5 安全考虑

- API Key 存储：用 Android Keystore 加密，不直接写明文
- 凭据传递：通过环境变量（`GLM_API_KEY`）而非文件，避免 rootfs 内泄漏
- 日志脱敏：LogRedactor 已覆盖 `api_key`/`token`/`sk-` 前缀，需确认 GLM key 格式也被覆盖

## 十、测试与质量分析（deepseek-worker-3 补充）

### 10.1 测试现状

| 维度 | 数量 | 说明 |
|---|---|---|
| 单元测试 | ~14 个方法 / 6 文件 | 覆盖 BundleManager/ProcessRunner/State/DeviceProfile/LogRedactor/BridgePolicy |
| Instrumented 测试 | **0** | build.gradle.kts 声明了依赖但无 src/androidTest/ 目录 |
| UI 测试 | **0** | Compose ui-test-junit4 依赖已声明但未写任何测试 |
| 集成测试 | **0** | Sandbox 生命周期/SandboxService/DshApp 启动流程均无测试 |

**严重缺失：**
- DefaultSandboxManager（324 行核心状态机）零测试
- SandboxSupervisor、OriginVerifier、BridgeRouter 零测试
- 所有 UI Screen（HomeScreen/SandboxScreen/TerminalScreen/FilesScreen 等）零测试
- 无边界/异常测试（空文件、损坏 bundle、并发访问、网络超时）
- 无恢复测试（SandboxSupervisor 崩溃恢复逻辑完全未测试）
- 无性能基准测试（bundle 解压/PRoot 启动/DSH 启动耗时）

### 10.2 产品化文档现状

| 文档 | 状态 | 问题 |
|---|---|---|
| README.md | 43 行 | ⚠️ 占位符 URL `your-name/DSHapp/releases`；缺截图/架构图/FAQ/故障排查 |
| THIRD_PARTY_NOTICES.md | 29 行 | ⚠️ 缺 commons-compress 声明；缺许可原文链接 |
| LICENSE | Apache-2.0 | ⚠️ Copyright 占位符 `DSHapp contributors` 需填写实际版权方 |

### 10.3 CI/CD 配置现状

GitHub Actions（46 行）已配置：push/PR 触发、JDK 17、testDebugUnitTest + lintDebug + assembleDebug。

**严重缺失：**
- 无 release 构建和签名发布
- 无 instrumented 测试（需 emulator）
- 无 runtime-bundle 构建
- 无代码覆盖率（JaCoCo/Kover）
- 无静态分析（Detekt/SpotBugs）和依赖扫描
- 无二进制大小检查
- 无冒烟测试

### 10.4 安全风险点（补充）

- ⚠️ **DevInstallReceiver 导出风险**：`android:exported="true"`，任何应用可发送广播。代码有 `if (!BuildConfig.DEBUG) return` 保护，但建议 release 构建中用 `tools:node="remove"` 移除
- ⚠️ **无数据静态加密**：沙箱用户数据在 `app/files/user-data/` 无加密
- ⚠️ **BridgeRouter 安全策略不完整**：`isTrustedDshWebUi()` 标注"placeholder"

### 10.5 补充优化点（来自质量视角）

| 优先级 | 编号 | 优化点 |
|---|---|---|
| P0 | #16 | README.md 占位符 URL |
| P0 | #17 | LICENSE Copyright 占位符 |
| P0 | #18 | DevInstallReceiver release 构建移除 |
| P1 | #19 | CI 增加 release 构建和签名 |
| P1 | #20 | 增加 DefaultSandboxManager 单元测试 |
| P1 | #21 | 增加代码覆盖率工具（目标 >60%） |
| P1 | #22 | 增加 CI 安全扫描 |
| P2 | #23 | 增加性能基准测试 |
| P2 | #24 | 增加 CHANGELOG/CONTRIBUTING/SECURITY 文档 |

## 十一、代码级缺陷分析（deepseek-worker-1 补充）

### 11.1 新发现的代码缺陷

| 编号 | 严重度 | 文件 | 问题 |
|---|---|---|---|
| HC-01 | **严重** | HttpHealthChecker.kt:57 | 注释说 200/302/404 都算存活，但代码只接受 200-299。302 重定向或 404 页面导致误判"未就绪" |
| SEC-01 | **严重** | ServiceLocator.kt:44 | `expectedDshToken = ""`，BridgeApi 全 no-op。任何本地网页可绕过能力检查 |
| AMD-01 | **严重** | (架构层) | 无 WebView 内嵌访问，DSH WebUI 完全依赖系统浏览器，App 无法注入 JS bridge |
| AMD-02 | **严重** | ServiceLocator.kt | BridgeApi 全部 stub，所有桥接方法返回空值/空列表 |
| RACE-01 | 中 | DefaultSandboxManager.kt:97-101 | restart() 调用 stop() 和 start() 时不持有 lifecycleMutex，中间窗口其他协程可介入 |
| RACE-02 | 中 | DefaultSandboxManager.kt | `healthLoopJob` 和 `runningProcess` 被不同线程访问但未标记 @Volatile |
| SHUT-01 | 中 | SandboxProcessRunner.kt | 仅用 SIGKILL 终止进程树，无 SIGTERM 优雅关闭阶段。DSH 会话可能丢失未保存数据 |
| SHUT-02 | 中 | DefaultSandboxManager.kt:134 | stopDsh() 是 TODO，无法单独停止 DSH 而不关闭整个沙箱 |
| LOG-01 | 中 | SandboxProcessRunner.kt | PRoot 输出写入单一日志文件，无轮转或大小限制，无限增长 |
| CONST-01 | 中 | Constants.kt | host/port/path 等均硬编码，无运行时配置能力 |
| DIAG-01 | 中 | DiagnosticsScreen.kt | 仅显示 PRoot 日志最后 30 行，无系统级诊断（内存/CPU/磁盘） |
| NOTI-01 | 低 | SandboxService.kt:126-131 | 部分通知文本为中文硬编码（非 strings.xml） |
| DEP-01 | 低 | install_dsh.sh | 固定 DSH_VERSION=0.1.0-rc.6，需更新 |
| GIT-01 | 低 | (仓库层) | 项目仅一个 initial commit，无版本历史 |

### 11.2 代码质量正面评价

deepseek-worker-1 同时确认了以下正面设计决策：

- ✅ 完善的 Kotlin Coroutine 使用（SupervisorJob + Dispatchers.IO + Mutex）
- ✅ 严格的 Bundle 提取安全验证（路径穿越防护 + 符号链接安全检查）
- ✅ 双槽位运行时更新机制，支持回滚
- ✅ LogRedactor 敏感信息脱敏
- ✅ 前台服务保活 + START_STICKY（Android 最佳实践）
- ✅ 注释质量高，关键设计决策有文档说明
- ✅ 网络安全配置正确（仅 127.0.0.1/localhost 允许 cleartext）
- ✅ /etc/resolv.conf 自动修复（WSL 兼容性问题已解决）
- ✅ 文件浏览器保护系统目录和 .dsh 目录不被误删改

## 十二、构建/依赖/配置分析（deepseek-worker-2 补充）

### 12.1 依赖缺失清单

| 缺失依赖 | 影响 | 推荐方案 |
|---|---|---|
| JSON 库（kotlinx-serialization/moshi） | BridgeApi 序列化、配置读写 | kotlinx-serialization |
| 网络库（Retrofit/OkHttp/Ktor） | HttpURLConnection 裸用，无拦截器/重试 | OkHttp + Retrofit 或 Ktor Client |
| 图片加载库（Coil/Glide） | 后续图标/缩略图加载 | Coil（Compose 原生支持） |
| 导航库（Navigation-Compose） | 手动 4-tab 状态管理，扩展受限 | navigation-compose |
| YAML 库 | DSH 配置文件读写（t2 必需） | SnakeYAML 2.2 |

### 12.2 构建优化机会

| 优先级 | 优化项 | 说明 |
|---|---|---|
| 高 | Kotlin 升级 2.0.21→2.1.10 | 更好的 Compose 编译器支持 |
| 中 | 启用 Gradle Configuration Cache | 冷构建速度提升 30-50% |
| 中 | 添加 Baseline Profile | 提升 Compose UI 启动性能 |
| 中 | R8 全模式 | 替换 ProGuard，更优代码缩减 |
| 低 | APK 拆分（AAB/多架构） | 目前 ~345MB，可拆 arm64-v8a 专属 |
| 低 | 添加 Koin 轻量 DI | 替代手动 ServiceLocator |

### 12.3 当前 Gradle 配置正面评价

- ✅ `parallel=true` + `caching=true` + `jvmargs=-Xmx4096m`
- ✅ AGP 8.9.2（最新）
- ✅ Compose BOM 2024.12.01
- ✅ Version catalog 已建立（libs.versions.toml）
- ✅ ProGuard 配置 release 缩减已启用