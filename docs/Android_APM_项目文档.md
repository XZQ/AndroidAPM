# Android APM 项目文档

> 文档同步：2026-07-11｜24 个构建单元｜135 个主源码文件（130 Kotlin + 4 C + 1 proto）｜70 个测试文件

## 一、项目结论

AndroidAPM 是模块化 Android 端 APM SDK。它覆盖采集、统一事件、开销保护、持久化、跨进程交接和上传；生产 Collector、查询聚合、告警、Dashboard、Native 符号化及托管服务不在仓库内。

当前最成熟的部分不是单个监控算法，而是所有模块共享的可靠事件管线：

```text
monitor module
  -> Apm.emit
  -> bounded queue (2048)
  -> optional aggregation / rate limit / PII sanitization
  -> appendBatch (up to 32)
  -> SQLite durable outbox (50,000)
  -> PersistentUploadWorker
  -> batch/custom uploader
  -> integrator-owned collector
```

关键事件可同步到 durable hand-off point。上传成功后删除，失败保留并重试；这是 at-least-once，不是 exactly-once，网络响应不确定时可能产生重复事件。

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
| 最新 runtime 实现提交（文档同步前） | `7922c99 Feat: Integrate SDK self-diagnostics` |
| root Gradle subproject | 22 |
| included build | 2：`apm-plugin`、`build-logic` |
| 总构建单元 | 24 |
| 主源码 | 135：130 Kotlin + 4 C + 1 proto |
| 测试文件 | 70 |
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
| IO | 流代理、慢/主线程 IO、FD/Closeable、PLT Hook | 包装流；Native 依赖运行时 xhook |
| Battery | 电量、CPU、WakeLock/GPS/Alarm | 后三类为宿主回调 |
| SQLite | 慢 SQL、主线程 DB、大影响行、QueryPlan | wrapper 或手动回调 |
| WebView | 页面/JS/白屏/Bridge/Console/资源瀑布 | 宿主转发回调，无通用自动注册 |
| IPC | Binder 耗时、主线程阈值、聚合 | `onBinderCallComplete`，无通用自动 Hook |
| Thread | 数量、同名线程、BLOCKED 状态 | 无线程池 backlog 自动监控 |
| GC | GC 次数/耗时、Heap、分配和回收率 | 定时运行时采样 |
| Render | View 数量/层级 | overdraw 与 draw-time 检测未交付 |

### 4.3 扩展与工具

| 模块 | 作用 |
|---|---|
| `apm-trace` | 手动 Span/Trace API，结束后通过 `Apm.emit` 上报 |
| `apm-otel-exporter` | 输出 OTel-compatible Span/Metric/Log Map；不负责 SDK/网络发送 |
| `apm-plugin` | AGP instrumentation + ASM slow-method 插桩 |
| `build-logic` | Android library convention plugin |
| `apm-sample-app` | 集成 15 个监控模块的演示应用，默认 Logcat 输出 |

## 五、统一事件模型

`ApmEvent` 的主要维度：

- identity：`module`, `name`, `kind`
- urgency：`severity`, `priority`
- occurrence：`timestamp`, `processName`, `threadName`
- context：`scene`, `foreground`, `globalContext`, `extras`
- payload：`fields`

`severity` 表示问题严重程度，`priority` 表示存储/上传顺序与资源竞争时的保留价值，两者不应混用。

持久化 `ApmEventCodec`：

- format version 1
- 最大 payload 2 MiB
- 单字符串最大 1 MiB
- 单 Map 最大 4096 项
- 可逆保存事件结构，但 `fields` 中任意值会通过 `toString()` 归一为字符串

传输支持 Line Protocol 和零外部 runtime 依赖的 Protobuf writer。当前事件模型没有稳定的 eventId/idempotency key。

## 六、分发与宿主开销

`Apm.emit` 在调用线程捕获时间戳、线程名和业务上下文快照，把事件构建延迟到 dispatcher worker。队列容量 2048，使用非阻塞 `offer`；满载时丢弃并计入自监控，避免监控 SDK 卡住业务线程。

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

- schema v2，WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启
- 默认容量 50,000
- 批量事务写入
- 缓存行数，每 512 次写入重同步
- 高优先级先上传；超容量时低优先级、旧事件先淘汰
- 坏 payload 行隔离删除

`PersistentUploadWorker`：

- 单 worker 顺序读取 outbox
- `BatchApmUploader` 一批一次请求；普通 uploader 逐条 fallback
- 成功后 delete；失败 markRetry
- 指数退避与 `Retry-After` 取较大值
- retry ≥ 10 或事件超过 7 天后 prune
- 没有并发 claim/lease，因此不能直接扩展为多 worker 或跨进程共同上传

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

SDK 自诊断与普通 APM 事件是两个故障域：`ApmLogger` 继续输出 Logcat，同时把受控记录写入 200 条内存环和 app-private 滚动 JSONL；文件写入通过容量 256 的非阻塞队列和 `apm-diagnostics-writer` 后台线程完成。默认保留 3 个 512 KiB 分片，总预算约 1.5 MiB。

`ApmDiagnostics.status/snapshot/exportTo/clear` 支持现场状态、最近记录、ZIP 导出和清理。导出与 snapshot 可以读取本地文件；普通日志写入调用线程不做文件 IO。文件异常只更新本地 sink 状态并降级到内存 + 原始 Logcat，不重新进入 logger，避免递归。

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
| `enableSelfMonitoring` | true | 60s 健康报告 |
| `enableAutoThrottle` | true | 丢弃率/延迟异常时停模块 |
| `diagnostics.enabled` | true | 独立本地诊断 journal |
| `enableMultiProcessCoordination` | false | 子进程 hand-off 关闭 |
| `enableHttpGzip` | true | 默认 endpoint HTTP 压缩 |

## 十一、构建与发布

根构建统一 group/version、POM 元数据、sources JAR/AAR 和可选 signing。`build-logic` 收敛 20 个 Android library 的 compileSdk/minSdk/Java 版本。`apm-plugin` 作为 included build 独立测试。

2026-07-11 在 JDK 21.0.11 执行的开发验证：

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
```

同日执行的完整发布验证链：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

全部命令通过。现场产物与报告为：

- 75 个 JUnit XML 测试套件、501 个测试，0 failures / 0 errors / 0 skipped；
- 21 份 `lint-results-debug.html`；
- `apm-sample-app-release-unsigned.apk`，4,589,624 字节；
- Maven Local 下当前 `com.apm:*-0.1.0` 发布包含 20 个 AAR、22 个 JAR、21 个 POM；
- 独立 `smoke-tests/maven-consumer` 清理后重新解析本地制品并构建成功。

仓库没有外部 Maven 发布凭据或已完成的 Maven Central 发布；`publishToMavenLocal` 成功不代表外部仓库已发布。

## 十二、测试策略

70 个测试文件覆盖配置默认值、事件 codec/Protobuf、dispatcher、PII、聚合/指纹、限流、durable outbox、SQLite/Robolectric、HTTP socket/Gzip/Retry-After、IPC 文件、SDK 诊断脱敏/JSONL/滚动/导出/并发降级、JNI 静态绑定契约、ASM 正常/异常出口、各模块核心计算和手动入口。

测试通过不能代替以下验证：

- 真机 Native 行为与符号化
- 进程被杀/断电/磁盘满场景
- 长时间功耗与内存开销
- 多 OEM/Android 版本兼容
- 真实 Collector 协议与服务端幂等

## 十三、当前未完成项

### P0：产品闭环

- 生产 Collector、鉴权、租户、数据模型、查询、聚合、告警、Dashboard
- eventId/idempotency 与重复事件处理协议

### P1：交付与正确性

- 多 worker/cross-process upload claim/lease/expiry
- 明确 typed field schema，避免 durable round-trip 后数值只保留字符串
- 完成或删除 `enableBinderHook`、`enableAutoRegister`、`detectOverdraw`、线程池 backlog 等未落地开关
- Native crash/ANR/IO 的真机矩阵和符号化链路

### P2：工程发布

- 真机 soak、功耗、磁盘与主线程开销报告
- Maven Central/外部私服发布
- 云端 CI；`.github/` 当前为本地忽略目录
- Release/lint/publish/smoke 对当前 tip 的周期性全量验证

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
