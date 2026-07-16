# AndroidAPM 项目交接快照

> 同步日期：2026-07-16｜分支：`develop`｜当前 tip 请执行 `git log --oneline -n 10`

## 结论

当前仓库是已成型的 Android APM 客户端 SDK：15 个监控模块、5 个基础模块、2 个扩展模块、一个示例应用、一个非发布 benchmark 模块、一个 ASM 插件 included build 和一个 convention-plugin included build。

端上事件管线、稳定 eventId、SQLite durable outbox、并发 upload lease、动态短期鉴权、签名远程配置/kill switch/采样/限流/endpoint、批量上传、显式监控接入和 benchmark harness 已有测试与本地构建证明。生产 Collector、查询/告警后台、服务端幂等、外部 Maven 发布和真机长稳数值属于外部建设，统一由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 管理。

生产可靠性以宿主安全优先：dispatcher 单事件 recoverable failure 不终止共享 worker，fatal VM error 不伪装成 drop/retry；outbox stale 删除计数不降到 0 以下，Retry-After 等待上限 60 秒，自定义同步 uploader 的阻塞终止由宿主负责；diagnostics 显式导出失败返回结果数据而不抛回支持流程。

## 事实源

1. 当前源码/构建文件
2. 当前测试与构建输出
3. `AGENTS.md`
4. `docs/Android_APM_项目文档.md`
5. `docs/architecture/00_整体架构.md`
6. 对应模块文档

不要从旧提交号或模型记忆推导当前状态。

## 当前工程清单

| 项目 | 数量/版本 |
|---|---|
| root subproject | 24 |
| included build | 2：`apm-plugin`, `build-logic` |
| 构建单元 | 26 |
| 基础模块 | 5 |
| 监控模块 | 15 |
| 扩展模块 | 2 |
| 主源码 | 154：149 Kotlin + 4 C + 1 proto |
| 测试/benchmark 文件 | 92 |
| JDK | 17 |
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.2.21 |
| Android | compileSdk 34 / minSdk 24 / targetSdk 34 |
| JVM bytecode | Java 17 |

主构建、两个 included build 和独立 Maven consumer 都会在 settings 阶段拒绝非 JDK 17 运行时；Java/Kotlin 编译与发布制品统一为 Java 17 字节码。

远程配置里程碑的源码和文档位于同一交付提交；不要在交接文档内固化会立即过期的“最新提交号”，使用 Git 历史判断实际 tip。

## 30 秒运行时理解

```text
Apm.init
  -> create logger/store/uploader/limiter/dispatcher
  -> optional signed config/coordinator/self-monitor
  -> start registered modules

Apm.emit
  -> bounded queue 2048
  -> signed sampling/aggregate/dynamic rate-limit/sanitize when enabled
  -> batch transaction, up to 32 events
  -> SQLite outbox 50K
  -> persistent worker
  -> uploader
  -> external collector
```

关键语义：

- Crash 可同步落盘，不同步做网络。
- 每个事件的稳定 `eventId` 贯穿所有 wire/storage/IPC 格式。
- Worker 先原子 claim；只有 owner 可 ACK/失败释放，租约过期可重领。
- retry ≥ 10 或 age > 7 天清理。
- 网络完成不确定时仍可能重传；服务端必须按 `eventId` 幂等。
- FileEventStore 是非 durable 兼容路径。
- SDK 自诊断使用条数 + 字节双预算内存环/队列和按进程隔离的 app-private 滚动文件，不经过 dispatcher/outbox/uploader；支持全进程聚合导出和 executor 异步读取。

## 接入现实

注册即可工作的能力：Java Crash、ANR 双通道、Memory 周期采样、Launch 生命周期、FPS、Thread/GC 周期采样、Render View 树与 FrameMetrics。

需要宿主接线：

- Network：OkHttp Interceptor/EventListener 或手动 callback
- SQLite：`ApmSQLiteDatabase` 或 `onSqlExecuted`
- IPC：`traceBinderCall` 或 `onBinderCallComplete`
- WebView：对指定实例 `install/uninstall`、delegate wrapper 或页面/JS/资源 callback
- Thread Pool：显式 `registerThreadPool(name, ThreadPoolExecutor)`
- Battery：WakeLock/GPS/Alarm callback
- IO：stream wrapper；Native path 还依赖 xhook
- Slow Method：宿主应用 Gradle ASM 插件

不宣称无法由公共 API 支撑的能力：通用 Binder hidden hook、进程级 WebView 自动接管、通用线程 leak 判断和 GPU overdraw 计数。对应旧字段已弃用并默认关闭，真实能力使用显式 API；FrameMetrics 和注册线程池 backlog 已实现。

初始化方式也必须明确二选一：手动 `Apm.init` 的宿主从合并 manifest 移除 `ApmInitProvider`；自动模式保留 Provider 并配置 `com.apm.config_class`。Sample 使用手动模式，并为 IO wrapper、`ApmSQLiteDatabase`、WebView install、IPC trace、线程池注册及 Battery 回调提供可运行入口。

## 默认配置注意

- endpoint 空：Logcat，不会上送生产服务
- dynamic HTTP endpoint：默认关闭；开启也只接受无凭据 HTTPS URL
- static HTTP headers / per-request header provider：默认空；长期密钥不能打进 APK
- SQLite durable storage：开启
- PII sanitization：关闭
- aggregation：关闭
- multi-process coordination：关闭
- self-monitor/auto-throttle：开启
- self-diagnostics：开启；200 条 / 4 MiB 内存、256 条 / 4 MiB 队列、每进程 3 × 512 KiB 文件；不自动上传
- native crash：关闭
- Hprof/fork dump：关闭

生产接入必须明确 endpoint/短期鉴权、固定 Ed25519 公钥、匿名稳定 installationId、隐私规则、采样限流、进程策略和服务端幂等。`apm-remote-config` 验签成功并同步写入 app-private LKG 后才发布；过期/验签失败/rollback/equivocation 都回退可信旧值或本地默认值。

## 验证

2026-07-16 已在 JDK 17.0.14 对客户端收口后的当前 tip 执行：

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat :apm-model:test --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

全部通过。Root Gradle XML 报告合计 94 个套件、595 个测试，0 failures / 0 errors / 0 skipped；included `apm-plugin` 另有 18 个测试通过。生成 23 份 lint HTML、4,708,872 字节的 sample unsigned Release APK，以及 Maven Local 中 21 个 AAR、23 个 JAR、22 个 POM。`apm-benchmark` 未发布，Release 与 AndroidTest Kotlin 已编译；独立 consumer 已从本地制品清理重建成功。model、Android library、Gradle plugin 与 sample 的代表性 class 文件均为 major version 61（Java 17）。

设备侧可见 Xiaomi `22041216UC` 和 Android 17 emulator。物理机安装被 `INSTALL_FAILED_USER_RESTRICTED` 拒绝；emulator 抑制预期 `EMULATOR` 门禁后完成 3 个 benchmark 方法并产出 JSON/Perfetto，但 runner 结束阶段因 `IsolationActivity` 启动超时使 Gradle task 失败。因此 instrumentation 入口已实际执行，物理性能验收仍需要设备允许测试 APK 安装后重跑，不能使用模拟器数值替代。

## 新电脑接手

1. 克隆仓库并切到 `develop`。
2. 确认 `JAVA_HOME` 指向 JDK 17；settings 会拒绝使用其他 JDK 版本。
3. 安装 Android SDK 34 和项目需要的 NDK/CMake。
4. 按 AGENTS 读序阅读。
5. 执行：

```powershell
git status --short --branch
git log --oneline -n 10
./gradlew.bat testDebugUnitTest
./gradlew.bat :apm-model:test
./gradlew.bat assembleDebug
./gradlew.bat -p apm-plugin test
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
```

6. 发布相关变更再执行：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug
```

## 后续优先级

客户端代码可独立完成的既定缺口已经收口，包括动态短期 Token、签名配置、LKG、全局/模块 kill switch、动态采样/限流和 HTTPS endpoint 轮换。后续事项均需要 Collector、平台凭据、CI 管理员、符号服务或真实设备，按独立 `AndroidAPM-Server` 仓库中 `docs/云端待建设清单.md` 的 P0/P1/P2、协议和验收条件推进。typed fields 也必须与 Collector 做版本化 schema 演进，不能在客户端静默改变 wire 类型。

## Git 与文档策略

- `docs/` 纳入 Git 并随代码同步。
- `.workbuddy/`、`.github/`、`.claude/` 保持忽略。
- 不在交接文档中写“当前最新文档提交”；使用 `git log`。
- 代码/架构/构建/测试变化后同步 AGENTS、项目文档和对应模块文档。
- 对外能力或接入方式变化后同步 README。
