# AndroidAPM 项目交接快照

> 同步日期：2026-07-22｜分支：`develop`｜当前 tip 请执行 `git log --oneline -n 10`

## 结论

当前仓库是已成型的 Android APM 客户端 SDK：15 个监控模块、5 个基础模块、2 个扩展模块、一个单依赖分发 Bundle、一个示例应用、一个非发布 benchmark 模块、一个 ASM 插件 included build 和一个 convention-plugin included build。

端上事件管线、单依赖 `apm-bundle` 分发、稳定 eventId、typed durable codec v3/legacy 读取、SQLite durable outbox、并发 upload lease、单事件/总量 payload 预算、动态短期鉴权、签名远程配置/kill switch/采样/限流/endpoint、优先级感知背压与单模块高水位隔离、业务上下文同步契约/异步 LKG 缓存、带迟滞恢复的 AutoThrottle、默认 PII 保护、配置/payload 快照、批量上传、显式监控接入，以及固定 time/allocation 预算与 fail-closed verifier 已有测试和本地构建证明。生产 Collector、查询/告警后台、服务端幂等、外部 Maven 发布、云端 runner 和真机长稳数值属于外部建设，统一由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 管理。

生产可靠性以宿主安全优先：dispatcher 单事件 recoverable failure 不终止共享 worker，fatal VM error 不伪装成 drop/retry；75% 高水位后，单一 NORMAL/LOW 模块默认最多占总队列容量 50%，HIGH/CRITICAL 不受该隔离门禁影响。dispatcher 仍是单 worker，该措施隔离入口容量而非增加并行吞吐。outbox stale 删除计数不降到 0 以下，Retry-After 等待上限 60 秒，自定义同步 uploader 的阻塞终止由宿主负责；diagnostics 显式导出失败返回结果数据而不抛回支持流程。

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
| root subproject | 25 |
| included build | 2：`apm-plugin`, `build-logic` |
| 构建单元 | 27 |
| 基础模块 | 5 |
| 监控模块 | 15 |
| 扩展模块 | 2 |
| 分发 Bundle | 1：`apm-bundle` |
| 主源码 | 157：152 Kotlin + 4 C + 1 proto |
| 测试/benchmark 文件 | 95 |
| Gradle runtime | JDK 17+ |
| Java toolchain | 17 |
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.2.21 |
| Android | compileSdk 34 / minSdk 24 / targetSdk 34 |
| JVM bytecode | Java 17 |

主构建、两个 included build 和独立 Maven consumer 都使用 Java 17 toolchain，并允许 Gradle/AGP 支持的更新 JDK 作为 Gradle runtime；Java/Kotlin 编译与发布制品统一为 Java 17 字节码。

远程配置里程碑的源码和文档位于同一交付提交；不要在交接文档内固化会立即过期的“最新提交号”，使用 Git 历史判断实际 tip。

## 30 秒运行时理解

```text
Apm.init
  -> create logger/store/uploader/limiter/dispatcher
  -> optional signed config/coordinator/self-monitor
  -> start registered modules

Apm.emit
  -> caller timestamp/thread/payload + synchronous or cached bizContext snapshot
  -> priority-aware bounded queue 2048
     -> default 75% high-water / 50% per-module NORMAL/LOW share
  -> signed sampling/aggregate/dynamic rate-limit/default sanitize
  -> batch transaction, up to 32 events
  -> SQLite outbox 50K / 64 MiB live payload / 256 KiB per event
  -> persistent worker
  -> uploader
  -> external collector
```

关键语义：

- Crash 可同步落盘，不同步做网络。
- 每个事件的稳定 `eventId` 贯穿所有 wire/storage/IPC 格式。
- Worker 先原子 claim；只有 owner 可 ACK/失败释放，租约过期可重领。
- `maxRetries` 是首次尝试后的重试次数；失败达到 `maxRetries + 1` 后立即清理，age > 7 天也清理。
- 网络完成不确定时仍可能重传；服务端必须按 `eventId` 幂等。
- FileEventStore 是非 durable 兼容路径。
- SDK 自诊断使用条数 + 字节双预算内存环/队列和按进程隔离的 app-private 滚动文件，不经过 dispatcher/outbox/uploader；每个 `sdk_health` 先写独立数值摘要，再以 HIGH 优先级尝试事件通道；`dispatcherModuleIsolationDropCount` 单列模块隔离丢弃且同时计入总 drop；支持全进程聚合导出和 executor 异步读取。

## 接入现实

注册即可工作的能力：Java Crash、ANR 双通道、Memory 周期采样、Launch 生命周期、FPS、Thread/GC 周期采样、Render View 树与 FrameMetrics。

需要宿主接线：

- Network：OkHttp Interceptor/EventListener、显式 `traceHttpUrlConnection` 或手动 callback
- HttpURLConnection helper：读取 `responseCode` 执行，不读正文、不 disconnect、不提供伪分阶段数据
- SQLite：`ApmSQLiteDatabase` 或 `onSqlExecuted`
- IPC：`traceBinderCall` 或 `onBinderCallComplete`
- WebView：对指定实例 `install/uninstall`、delegate wrapper 或页面/JS/资源 callback
- Thread Pool：显式 `registerThreadPool(name, ThreadPoolExecutor)`
- Battery：WakeLock/GPS/Alarm callback
- IO：stream wrapper；Native path 还依赖 xhook
- Slow Method：宿主应用 Gradle ASM 插件

不宣称无法由公共 API 支撑的能力：通用 Binder hidden hook、进程级 WebView 自动接管、通用线程 leak 判断和 GPU overdraw 计数。对应旧字段已弃用并默认关闭，真实能力使用显式 API；FrameMetrics 和注册线程池 backlog 已实现。

初始化方式也必须明确二选一：手动 `Apm.init` 的宿主从合并 manifest 移除 `ApmInitProvider`；自动模式保留 Provider 并配置 `com.apm.config_class`。Sample 使用手动模式，并为 IO wrapper、`ApmSQLiteDatabase`、WebView install、IPC trace、线程池注册及 Battery 回调提供可运行入口。

完整能力宿主可只依赖 `com.apm:apm-bundle:0.1.0`；该制品通过 POM 传递暴露全部 22 个运行时模块，但不会自动初始化、注册模块或应用 `com.apm.slow-method`。体积敏感宿主仍应按需选择细粒度模块。

## 默认配置注意

- endpoint 空/未知 scheme：安全确认后丢弃，不输出 payload；开发 Logcat 必须显式 `logcat://...`
- dynamic HTTP endpoint：默认关闭；开启也只接受无凭据 HTTPS URL
- static HTTP headers / per-request header provider：默认空；长期密钥不能打进 APK
- SQLite durable storage：开启
- durable payload：单事件软上限 256 KiB；活跃 payload 逻辑预算 64 MiB，超限按低优先级旧事件淘汰并计入自监控
- PII sanitization：开启；文本正则和高置信敏感字段名同时生效
- debug logging：关闭
- aggregation：关闭
- multi-process coordination：关闭
- self-monitor/auto-throttle：开启
- dispatcher module isolation：开启；75% 高水位 / 单模块 50% 总容量上限，仅约束 NORMAL/LOW
- biz context：默认 `SYNCHRONOUS` 精确事件时刻快照，provider 必须 O(1)/无 IO/无等待锁；慢 provider 使用 `ASYNC_CACHED`，默认 1s 后台刷新并保留 LKG
- FPS report interval：1000ms 单调时间窗口；旧 `windowSize` 仅兼容保留
- self-diagnostics：开启；200 条 / 4 MiB 内存、256 条 / 4 MiB 队列、每进程 3 × 512 KiB 文件；不自动上传
- native crash：关闭
- Hprof/fork dump：关闭

AutoThrottle 退化立即生效；只有连续 3 个周期满足 drop rate <= 20% 且平均上传延迟 <= 3 秒才恢复。迟滞区间或再次退化会清零连续健康计数。SQLite 的 64 MiB 是活跃 payload 逻辑预算，不包含 page/WAL 物理开销；活动 upload lease 不为容量回收让路，因此可在租约释放或过期前临时超预算。

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

全部通过。Root Gradle XML 报告合计 94 个套件、595 个测试，0 failures / 0 errors / 0 skipped；included `apm-plugin` 另有 18 个测试通过。生成 23 份 lint HTML、4,708,872 字节的 sample unsigned Release APK，以及 Maven Local 中 21 个 AAR、23 个 JAR、22 个 POM。`apm-benchmark` 未发布，Release 与 AndroidTest Kotlin 已编译；独立 consumer 已从本地制品清理重建成功。model、Android library、Gradle plugin 与 sample 的代表性 class 文件均为 major version 61（Java 17）。另用 JDK 21.0.11 启动 Gradle 后，根构建配置和 `:apm-model:test` 成功，model class 仍为 major version 61。

2026-07-21 的 P0 hardening 增量验证：JDK 17.0.14 下 `:apm-core:testDebugUnitTest --no-daemon` 通过 23 suites / 153 tests，`python docs/verify_docs.py` 通过 40 Markdown / 37 links。根测试复验没有形成新通过基线：运行中共享 Gradle 8.13 distribution/transform cache 被外部清理，导致 Kotlin daemon 缺少 classpath snapshot，wrapper 重下载又超时。接手时应在稳定缓存环境重新执行根命令；在此之前保留 2026-07-16 的 595-test 全量结果，不把本次环境失败解释成源码回归。

2026-07-22 的第二批定向验证：JDK 17.0.14 下 `:apm-core:testDebugUnitTest :apm-storage:testDebugUnitTest --no-daemon` 通过 core 23 suites / 160 tests 与 storage 6 suites / 36 tests，均为 0 failures/errors/skips；storage 在分块 trim 加固后另以 `--rerun-tasks` 完整重跑 36 个测试并通过。`python docs/verify_docs.py` 通过 40 Markdown / 37 links。该结果覆盖 AutoThrottle 迟滞恢复、单 payload 隔离、总字节预算、分块回收和存储降级可观测性，但不是新的全根测试基线。

2026-07-22 的第三批定向验证：JDK 17.0.14 下以 `--rerun-tasks` 强制重跑 FPS 4 suites / 31 tests 与 core 23 suites / 162 tests，均为 0 failures/errors/skips；补入 API 26 delayed-frame 门禁后又执行 `:apm-fps:testDebugUnitTest :apm-fps:lintDebug --no-daemon` 并通过。当前 `apm-fps`、`apm-core` lint 文本报告均为 `No issues found`，`python docs/verify_docs.py` 通过 40 Markdown / 37 links。该结果覆盖 FPS 单调时间窗口、FrameMetrics 无逐帧对象分配滚动累计、API 26 门禁和 `sdk_health` 双通道，但不是新的全根测试基线。

2026-07-22 的第四批 dispatcher isolation 定向验证：JDK 17.0.14 下 `:apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon` 通过 23 suites / 167 tests，0 failures/errors/skips，lint 为 `No issues found`；`python docs/verify_docs.py` 通过 40 Markdown / 37 links。确定性压力测试覆盖默认模块隔离、其他模块保留容量、HIGH 绕过并执行优先级淘汰、百分比约束、关闭开关、总 drop 与 `dispatcherModuleIsolationDropCount` 独立计数。该结果只证明入口容量隔离，不是 dispatcher 多 worker 吞吐或真机性能基线。

2026-07-22 的第五批 biz-context latency 定向验证：JDK 17.0.14 下 `:apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon` 通过 24 suites / 172 tests，0 failures/errors/skips，lint 为 `No issues found`；`python docs/verify_docs.py` 通过 40 Markdown / 37 links。测试覆盖同步不可变快照、异步 SDK 线程、首次空值/LKG、recoverable provider 失败、显式请求合并和公共 init/refresh/stop 生命周期。该结果证明异步模式 emit 不执行宿主 provider；同步兼容模式仍要求接入方履行 O(1)/无 IO/无等待锁契约。

2026-07-22 的第六批 consumer distribution 定向验证：JDK 17.0.14 下根 `publishToMavenLocal --no-daemon`、隔离 consumer `clean assembleDebug --no-daemon` 与 `:apm-bundle:lintDebug :apm-bundle:assembleRelease --no-daemon` 均通过。consumer 只声明 `com.apm:apm-bundle:0.1.0`；Maven Local 当前包含 22 AAR / 24 JAR / 23 POM，bundle POM 传递暴露 22 个 `com.apm` 运行时制品，AAR 不承载 SDK 实现类。`python docs/verify_docs.py` 通过 41 Markdown / 39 links。该结果只覆盖本地发布与单依赖消费，不代表外部 Maven 发布完成。

2026-07-22 的第七批 HttpURLConnection integration 定向验证：JDK 17.0.14 下 `:apm-network:testDebugUnitTest :apm-network:lintDebug --rerun-tasks --no-daemon` 通过 3 suites / 21 tests，0 failures/errors/skips，lint 为 `No issues found`；`python docs/verify_docs.py` 通过 41 Markdown / 39 links。显式 helper 只读取一次 `responseCode` 执行请求，把 status 交给宿主 block，不读取正文或 disconnect；测试证明 HTTP error 正常返回、headers/body transport IOException 保持原异常、非网络宿主异常不被误标、recoverable report 失败不覆盖宿主结果、fatal VM error 不被吞。真实代理/TLS/重定向/OEM 行为仍需集成验证。

2026-07-22 的第八批 performance-budget 定向验证：JDK 17.0.14 下 5 个 host verifier unittest 通过，已有 emulator JSON 只在显式 parser-only override 下完成 3 个预算比较，`:apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --rerun-tasks --no-daemon` 通过。预算固定 durable encode/decode 与 32-event SQLite batch 的 median time/allocation 上限；Gradle release gate 默认拒绝 emulator，并对缺项、坏指标和超预算 fail closed。`python docs/verify_docs.py` 通过 42 Markdown / 41 links。当前物理设备安装策略仍阻止接受真机运行，因此这里只证明 gate 实现，不声明物理预算通过。

2026-07-22 的第九批 typed-durable-field 定向验证：JDK 17.0.14 下 `:apm-model:test :apm-storage:testDebugUnitTest :apm-benchmark:compileReleaseAndroidTestKotlin --rerun-tasks --no-daemon` 通过 model 4 suites / 40 tests 与 storage 6 suites / 36 tests，均为 0 failures/errors/skips；benchmark 编译通过。codec v3 测试覆盖 12 类标量类型保真、任意对象字符串 fallback、4,096 字符大数边界、v1/v2 读取和未知 tag 拒绝，storage 全套覆盖 SQLite 重放链；`python docs/verify_docs.py` 通过 42 Markdown / 41 links。Line/Protobuf 仍是字符串 fields，Collector 契约未随本地格式升级。

设备侧可见 Xiaomi `22041216UC` 和 Android 17 emulator。物理机安装被 `INSTALL_FAILED_USER_RESTRICTED` 拒绝；emulator 抑制预期 `EMULATOR` 门禁后完成 3 个 benchmark 方法并产出 JSON/Perfetto，但 runner 结束阶段因 `IsolationActivity` 启动超时使 Gradle task 失败。因此 instrumentation 入口已实际执行，物理性能验收仍需要设备允许测试 APK 安装后重跑，不能使用模拟器数值替代。

## 新电脑接手

1. 克隆仓库并切到 `develop`。
2. 推荐 `JAVA_HOME` 指向 JDK 17；也可使用 Gradle/AGP 支持的更新 JDK，实际编译与测试仍由 Java 17 toolchain 执行。
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
./gradlew.bat :apm-benchmark:verifyReleasePerformanceBudgets
```

6. 发布相关变更再执行：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug
```

## 后续优先级

客户端代码可独立完成的既定缺口已经收口，包括 typed durable codec v3 与 legacy 读取、动态短期 Token、签名配置、LKG、全局/模块 kill switch、动态采样/限流、HTTPS endpoint 轮换、优先级感知入口背压、单模块高水位容量隔离、默认隐私保护，以及固定 time/allocation 预算与 fail-closed 物理设备 gate。后续事项均需要 Collector、平台凭据、CI 管理员、符号服务或真实设备，按独立 `AndroidAPM-Server` 仓库中 `docs/云端待建设清单.md` 的 P0/P1/P2、协议和验收条件推进。typed wire fields 仍必须与 Collector 做版本化 schema 演进，不能把本地 codec tag 直接解释成传输协议；dispatcher 多 worker/分区吞吐若后续推进，必须先证明 aggregator、rate limiter、sanitizer 与 SQLite 顺序语义和线程安全，不能把本次入口隔离误写成并行化。首次接受预算仍必须由专用真机产生，不能把模拟器 parser 证据伪装成发布通过。

## Git 与文档策略

- `docs/` 纳入 Git 并随代码同步。
- `.workbuddy/`、`.github/`、`.claude/` 保持忽略。
- 不在交接文档中写“当前最新文档提交”；使用 `git log`。
- 代码/架构/构建/测试变化后同步 AGENTS、项目文档和对应模块文档。
- 对外能力或接入方式变化后同步 README。
