# Android APM 项目文档

> 文档同步：2026-07-11｜25 个构建单元｜143 个主源码文件（138 Kotlin + 4 C + 1 proto）｜80 个测试/benchmark 文件

## 一、项目结论

AndroidAPM 是模块化 Android 端 APM SDK。它覆盖采集、统一事件、开销保护、持久化、跨进程交接和上传；生产 Collector、查询聚合、告警、Dashboard、Native 符号化及托管服务不在仓库内。

当前最成熟的部分不是单个监控算法，而是所有模块共享的可靠事件管线：

```text
monitor module
  -> Apm.emit
  -> bounded queue (2048)
  -> optional aggregation / rate limit / PII sanitization
  -> appendBatch (up to 32)
  -> SQLite durable outbox v3 (50,000, unique eventId)
  -> claim(owner, lease, expiry) -> PersistentUploadWorker
  -> batch/custom uploader
  -> integrator-owned collector
```

关键事件可同步到 durable hand-off point。每个事件拥有稳定 `eventId`，上传 Worker 原子 claim 后由 owner ACK/失败释放，租约过期可重领。上传成功后删除，失败保留并重试；这是 at-least-once，不是 exactly-once，网络响应不确定时服务端仍须按 `eventId` 去重。

## 二、事实源与接手顺序

事实优先级：

1. 当前源码与构建配置
2. 当前可执行测试/构建结果
3. 当前 Git 本地与远端状态
4. 本文档及其他说明材料

接手顺序：

1. `AGENTS.md`
2. 本文档
3. `docs/PROJECT_HANDOFF.md`
4. `README.md`
5. `CLAUDE.md`
6. `docs/architecture/00_整体架构.md`
7. 目标模块的 `docs/architecture/*.md`

`docs/` 属于项目交付并纳入 Git；`.workbuddy/`、`.github/`、`.claude/` 是本地状态，保持忽略。

## 三、工程基线

| 项目 | 当前值 |
|---|---|
| 分支 | `develop` |
| 最新 runtime 实现提交（文档同步前） | `210236f Fix: Warn on ignored legacy switches` |
| root Gradle subproject | 23 |
| included build | 2：`apm-plugin`、`build-logic` |
| 总构建单元 | 25 |
| 主源码 | 143：138 Kotlin + 4 C + 1 proto |
| 测试/benchmark 文件 | 80 |
| Kotlin | 2.2.21 |
| AGP | 8.13.2 |
| Gradle | 8.13 |
| JDK | 21 |
| JVM bytecode | Java 11 |
| Android SDK | compileSdk 34 / minSdk 24 / targetSdk 34 |

不要在文档中维护“最新文档提交号”；文档提交本身会让该值立即过期。使用 `git log --oneline -n 10` 查看实际 tip。

## 四、模块拓扑

### 4.1 基础模块

| 模块 | 核心职责 | 关键类 |
|---|---|---|
| `apm-model` | 统一事件、优先级、Line Protocol、Protobuf、持久化 codec | `ApmEvent`, `ApmEventCodec`, `ProtobufSerializer` |
| `apm-core` | 初始化、生命周期、分发、限流、聚合、脱敏、多进程、自监控、独立诊断日志 | `Apm`, `ApmDispatcher`, `ApmDiagnostics`, `PersistentUploadWorker` |
| `apm-storage` | SQLite outbox 与 File 兼容存储 | `SQLiteEventStore`, `FileEventStore`, `PendingEventStore` |
| `apm-uploader` | HTTP/Logcat/自定义传输和非 durable 重试兼容路径 | `HttpApmUploader`, `RetryingApmUploader` |

### 4.2 监控模块

| 模块 | 代码已经实现 | 必要接线与边界 |
|---|---|---|
| Memory | Heap/PSS/Native Heap、Activity/Fragment/ViewModel 泄漏、OOM、Hprof | 注册自动采样；ViewModel API 手动；dump 默认关闭 |
| Crash | Java crash、可选 Native signal、tombstone、ApplicationExitInfo | Java 默认；Native crash 默认关闭 |
| ANR | SIGQUIT flag、Watchdog、堆栈采样、traces、分类/去重 | 注册自动；Native 失败降级 Watchdog |
| Launch | 真实进程启动基线、冷/热/温、首帧、阶段 | Activity 自动；ContentProvider/App 阶段手动 |
| Network | OkHttp 全阶段、慢/错请求、聚合 | Interceptor/EventListener 或手动回调 |
| FPS | Choreographer、FrameMetrics、掉帧等级 | Activity 生命周期自动 |
| Slow Method | Looper Hook、栈采样、ASM | ASM 必须由宿主应用 Gradle 插件 |
| IO | 流代理、慢/主线程 IO、FD/Closeable、去重吞吐窗口、PLT Hook | 显式包装流；Native 依赖运行时 xhook；回调 no-throw |
| Battery | 电量、CPU、WakeLock/GPS/Alarm | 后三类为宿主回调 |
| SQLite | 慢 SQL、主线程 DB、大影响行、QueryPlan | wrapper rawQuery 对完整 SQL/参数做 QueryPlan；监控不改变 DB 结果 |
| WebView | 页面/JS/白屏/Bridge/Console/资源瀑布 | 对指定实例 install/uninstall、delegate wrapper 或手动回调 |
| IPC | Binder 耗时、主线程阈值、固定窗口聚合 | `traceBinderCall` / `onBinderCallComplete`，不使用隐藏 API |
| Thread | 数量、同名线程、BLOCKED、真实线程池 backlog | `ThreadPoolExecutor` 由宿主显式注册 |
| GC | GC 次数/耗时、Heap、分配和回收率 | 后台单调时钟采样；无效计数窗口跳过派生维度 |
| Render | View 数量/层级、API 24+ FrameMetrics | Activity 自动；公共 API 不支持 GPU overdraw 计数 |

### 4.3 扩展与工具

| 模块 | 作用 |
|---|---|
| `apm-trace` | 手动 Span/Trace API，结束后通过 `Apm.emit` 上报 |
| `apm-otel-exporter` | 输出 OTel-compatible Span/Metric/Log Map；不负责 SDK/网络发送 |
| `apm-plugin` | AGP instrumentation + ASM slow-method 插桩 |
| `build-logic` | Android library convention plugin |
| `apm-sample-app` | 集成 15 个监控模块的演示应用，默认 Logcat 输出 |
| `apm-benchmark` | 非发布 AndroidX Microbenchmark，覆盖 codec 与 SQLite outbox 热路径 |

## 五、统一事件模型

`ApmEvent` 的主要维度：

- identity：`eventId`, `module`, `name`, `kind`
- urgency：`severity`, `priority`
- occurrence：`timestamp`, `processName`, `threadName`
- context：`scene`, `foreground`, `globalContext`, `extras`
- payload：`fields`

`severity` 表示问题严重程度，`priority` 表示存储/上传顺序与资源竞争时的保留价值，两者不应混用。

持久化 `ApmEventCodec`：

- 当前写 format version 2，兼容读取 version 1
- 最大 payload 2 MiB
- 单字符串最大 1 MiB
- 单 Map 最大 4096 项
- 可逆保存事件结构，但 `fields` 中任意值会通过 `toString()` 归一为字符串

传输支持 Line Protocol 和零外部 runtime 依赖的 Protobuf writer；`eventId` 在 Line Protocol、Protobuf field 14、durable codec、SQLite 与多进程交接中保持稳定。服务端协议仍必须以该值实现幂等。

## 六、分发与宿主开销

`Apm.emit` 在调用线程捕获时间戳、线程名和业务上下文不可变快照，把事件构建延迟到 dispatcher worker。宿主 `bizContextProvider` 异常会记录 internal error 并降级为空上下文，不向业务调用栈外泄。队列容量 2048，使用非阻塞 `offer`；满载时丢弃并计入自监控，避免监控 SDK 卡住业务线程。

worker 单轮 drain 最多 32 条：

1. 解析 lazy event
2. 可选聚合
3. rate limit；ERROR/FATAL 绕过
4. 可选 PII 脱敏
5. `appendBatch` 单事务落盘
6. 唤醒 persistent uploader

停止顺序：先切断新事件入口，再停止模块，排空 dispatcher，处理聚合残留，停止 uploader，关闭 store。排空均有超时上限。

## 七、持久化与上传

默认 `SQLiteEventStore`：

- schema v3，v2 通过 additive migration 保留所有 pending 行并补 `legacy-<rowId>`；WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启
- 默认容量 50,000
- 批量事务写入
- 缓存行数，每 512 次写入重同步
- `event_id` 唯一约束，本地重复追加幂等
- `lease_owner` + `lease_expires_at`；SQLite 写事务保证跨 store/进程 read-and-claim 原子性
- 高优先级先上传；超容量时低优先级、旧事件先淘汰，活动租约不被 prune/trim
- 坏 payload 行隔离删除

`PersistentUploadWorker`：

- 每个实例生成唯一 owner，按 `uploadLeaseDurationMs` 原子 claim
- `BatchApmUploader` 一批一次请求；普通 uploader 逐条 fallback
- 成功后 owner-aware ACK；失败递增 retry 并释放；shutdown 主动释放 owner 全部 claim
- 指数退避与 `Retry-After` 取较大值
- 合并后的等待限制在 10 ms–60 s，负值或极端服务端 hint 不制造热循环/永久休眠
- retry ≥ 10 或事件超过 7 天后 prune
- owner 不匹配不能 ACK/失败修改；租约过期后其他 Worker 可重领

可靠性优先级是宿主安全、telemetry durability、diagnostic completeness。dispatcher 对单个 lazy factory/聚合/限流/脱敏的 recoverable `Exception` 单独降级，后续事件继续；fatal VM error 不转换成 drop。SQLite 的进程本地缓存计数在跨 store 删除后下限为 0，避免负缓存绕过容量淘汰。自定义同步 uploader 必须自行配置有界 IO，SDK 不尝试强杀任意宿主代码。

`StorageType.FILE` 是 500 行 ring buffer 兼容路径，不提供成功确认、重启重放等 durable 语义，初始化会输出降级警告。

## 八、多进程

多进程协调默认关闭。开启后：

- 主进程为 uploader process
- 子进程普通事件在单线程 executor 中合批
- 达到 100 行或等待 500 ms 后写文件
- 先写 `.tmp`，完成后发布为 `.ipc`
- critical 事件同步单文件发布
- 主进程每 5 秒扫描 ready 文件
- 文件 5 分钟过期

该通道是本机文件 hand-off，不是跨设备传输；rename 失败时存在 copy fallback，仍应将其视为尽力保持完整性的本地协调机制。

## 九、SDK 自诊断

SDK 自诊断与普通 APM 事件是两个故障域：`ApmLogger` 继续输出 Logcat，同时把受控记录写入 200 条 / 4 MiB 内存环和按进程隔离的 app-private 滚动 JSONL；文件写入通过 256 条 / 4 MiB 非阻塞队列和 `apm-diagnostics-writer` 后台线程完成。每个进程默认保留 3 个 512 KiB 分片，磁盘预算约 1.5 MiB。

`ApmDiagnostics.status/snapshot/exportTo/clear` 及 `snapshotAsync/exportToAsync/clearAllProcesses` 支持现场状态、最近记录、聚合 ZIP 导出和明确范围的清理。每个 Android 进程拥有独立 journal 目录；内存环和写队列默认各有 4 MiB 字节预算，并保留原有条数预算。`status` 使用缓存资源计数，snapshot/导出读取文件时推荐异步 API。冷却期 writer 不提前出队，读/写故障独立计数；文件异常只更新本地状态并降级到内存 + 原始 Logcat，不重新进入 logger，避免递归。显式导出失败返回 `DiagnosticExportResult(success=false)`，自定义 store 的异常也不会逃逸。导出最多读取最近 16 个进程目录，合并结果受 10,000 条 / 16 MiB 双上限约束；目标不能覆盖活动 segment，manifest 带 SDK/process/session 与截断元数据。

结构化记录包含时间、级别、组件、错误码、进程、线程、异常类型、有限堆栈与栈指纹。消息最大 4 KiB，异常栈最大 16 KiB/64 帧，并脱敏常见 token/password/Authorization。事件 payload、业务上下文、请求正文、SQL 不进入诊断 journal。SDK 不自动上传诊断包。

## 十、配置默认值

| 配置 | 默认 | 说明 |
|---|---:|---|
| `storageType` | `SQLITE` | durable outbox |
| `endpoint` | 空 | Logcat uploader |
| `rateLimitEventsPerWindow` | 10/60s | 按 module/name 分桶 |
| `enableAggregation` | false | 高频 metric 与 alert 去重不默认启用 |
| `enablePiiSanitization` | false | 生产需要显式隐私评审 |
| `enableRetry` | true | durable 路径由 persistent worker 负责 |
| `uploadBatchSize` | 20 | 单次 durable batch 上限 |
| `uploadLeaseDurationMs` | 120000 ms | 一次上传 owner 租约 |
| `enableSelfMonitoring` | true | 60s 健康报告 |
| `enableAutoThrottle` | true | 丢弃率/延迟异常时停模块 |
| `diagnostics.enabled` | true | 独立本地诊断 journal |
| `enableMultiProcessCoordination` | false | 子进程 hand-off 关闭 |
| `enableHttpGzip` | true | 默认 endpoint HTTP 压缩 |
| `IpcConfig.enableBinderHook` | false / deprecated | 无公共全局 Hook；使用显式 tracing |
| `WebviewConfig.enableAutoRegister` | false / deprecated | 无全局 WebView 注册；按实例 install |
| `ThreadMonitorConfig.enableThreadLeakDetect` | false / deprecated | 通用 leak 判断不可靠；显式注册线程池 |
| `RenderConfig.detectOverdraw` | false / deprecated | 公共 API 无 GPU overdraw 计数 |
| `IoConfig.enableAutoHook` | false / deprecated | 无公共全局 Java IO Hook；使用显式 stream wrapper |

各监控模块中历史遗留但没有对应运行时数据来源的 `maxStackTraceLength`（Battery/FPS/GC/Render）、Render 单 View `viewDrawThresholdMs` 和 Thread 通用 leak threshold 均保留为弃用兼容字段，不再描述为已生效能力。

## 十一、构建与发布

根构建统一 group/version、POM 元数据、sources JAR/AAR 和可选 signing。`build-logic` 收敛发布型 Android library 的 compileSdk/minSdk/Java 版本；`apm-benchmark` 直接应用官方 Benchmark 插件并明确排除 Maven publication。`apm-plugin` 作为 included build 独立测试。

2026-07-11 在 JDK 21.0.11 执行的开发验证：

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon
```

同日执行的完整发布验证链：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

全部命令通过。现场产物与报告为：

- root Gradle 82 个 JUnit XML 测试套件、553 个测试，0 failures / 0 errors / 0 skipped；included `apm-plugin` 另有 18 个测试通过；
- 22 份 `lint-results-debug.html`；
- `apm-sample-app-release-unsigned.apk`，4,687,968 字节；
- Maven Local 下当前 `com.apm:*-0.1.0` 发布包含 20 个 AAR、22 个 JAR、21 个 POM；
- `apm-benchmark` 未进入 Maven Local publication，Release 与 AndroidTest Kotlin 均编译成功；
- 独立 `smoke-tests/maven-consumer` 清理后重新解析本地制品并构建成功。

仓库没有外部 Maven 发布凭据或已完成的 Maven Central 发布；`publishToMavenLocal` 成功不代表外部仓库已发布。

## 十二、测试策略

80 个测试/benchmark 文件覆盖配置默认值、事件 identity/codec/Protobuf、dispatcher 单事件故障隔离/fatal 边界、PII、聚合/指纹、限流、durable outbox migration/lease/concurrency/固定种子状态机、GC 分配/回收窗口、IO 吞吐窗口、SQLite QueryPlan gate/现代 SCAN 解析、HTTP socket/Gzip/Retry-After、IPC 文件、SDK 诊断脱敏/JSONL/滚动/导出失败数据化/并发降级、JNI 静态绑定契约、ASM 正常/异常出口、Binder/线程池/WebView/FrameMetrics 核心计算，以及两个真机 Microbenchmark 入口。

测试通过不能代替以下验证：

- 真机 Native 行为与符号化
- 进程被杀/断电/磁盘满场景
- 长时间功耗与内存开销
- 多 OEM/Android 版本兼容
- 真实 Collector 协议与服务端幂等

## 十三、客户端完成边界与外部工作

仓库内可完成的客户端缺口已收口：稳定事件身份、SQLite v3 additive migration、本地去重、claim/lease/expiry、owner-aware ACK、显式 Binder/WebView/线程池公共 API、FrameMetrics、自诊断和 benchmark harness 均已实现。

一个保留的兼容边界是 `fields` 任意值在 durable round-trip 后归一为字符串；改变它需要版本化 typed-field wire schema，会影响 Collector，纳入云端协议共同设计而不是静默改格式。

生产 Collector、鉴权/租户、服务端幂等、查询聚合/告警/Dashboard、Native 后台符号化、外部 Maven 发布、云端 CI，以及真机 soak/功耗/热/磁盘数值全部依赖外部系统或设备。唯一任务清单和验收条件见 [`docs/云端待建设清单.md`](云端待建设清单.md)。

## 十四、设计原则

1. SDK 不能为了观测而阻塞宿主主流程。
2. 关键事件先到 durable hand-off point，再考虑网络。
3. 高优先级事件在限流、淘汰和上传顺序上获得保护。
4. Native/反射/Hook 能力失败时应可降级并自监控。
5. 自动能力与宿主手动接线必须在 API 和文档中明确区分。
6. 当前代码事实优先于历史文档与宣传性比较表。
7. 没有服务端查询和告警闭环时，不宣称完整 APM 产品完成。
8. APM 自身诊断不依赖被诊断的事件 dispatcher、outbox 或 uploader。

## 十五、文档与历史资料

- `docs/architecture/`：当前模块架构事实源
- `docs/APM_Review_2026-07-08.md`：历史评审与当前处置状态
- `docs/APM_Optimization_2026-07-08.md`：历史优化建议与落地状态
- `docs/architecture/generated-diagrams/`：由当前架构同步生成的 SVG/PNG
- `docs/APM_对比报告.docx`、`docs/APM_框架对比报告.docx`：可分发报告产物
- `docs/记录.zip`、`docs/绘制.jpeg`：历史参考资料，不作为当前代码证明
