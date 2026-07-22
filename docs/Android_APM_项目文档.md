# Android APM 项目文档

> 文档同步：2026-07-22｜26 个构建单元｜156 个主源码文件（151 Kotlin + 4 C + 1 proto）｜93 个测试/benchmark 文件

## 一、项目结论

AndroidAPM 是模块化 Android 端 APM SDK。它覆盖采集、统一事件、开销保护、持久化、跨进程交接、动态短期鉴权、签名远程配置和上传；生产 Collector、查询聚合、告警、Dashboard、Native 符号化及托管服务不在仓库内。

当前最成熟的部分不是单个监控算法，而是所有模块共享的可靠事件管线：

```text
monitor module
  -> Apm.emit
  -> priority-aware bounded queue (2048)
  -> signed dynamic sampling / optional aggregation / dynamic rate limit / default PII sanitization
  -> appendBatch (up to 32)
  -> SQLite durable outbox v3 (50,000 rows / 64 MiB live payload, 256 KiB per event, unique eventId)
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
| runtime tip | 使用 `git log --oneline -n 10` 查看；远程配置实现与本文档同一交付提交 |
| root Gradle subproject | 24 |
| included build | 2：`apm-plugin`、`build-logic` |
| 总构建单元 | 26 |
| 主源码 | 156：151 Kotlin + 4 C + 1 proto |
| 测试/benchmark 文件 | 93 |
| Kotlin | 2.2.21 |
| AGP | 8.13.2 |
| Gradle | 8.13 |
| Gradle runtime | JDK 17+ |
| Java toolchain | 17 |
| JVM bytecode | Java 17 |
| Android SDK | compileSdk 34 / minSdk 24 / targetSdk 34 |

不要在文档中维护“最新文档提交号”；文档提交本身会让该值立即过期。使用 `git log --oneline -n 10` 查看实际 tip。

## 四、模块拓扑

### 4.1 基础模块

| 模块 | 核心职责 | 关键类 |
|---|---|---|
| `apm-model` | 统一事件、优先级、Line Protocol、Protobuf、持久化 codec | `ApmEvent`, `ApmEventCodec`, `ProtobufSerializer` |
| `apm-core` | 初始化、生命周期、分发、限流、聚合、脱敏、多进程、自监控、独立诊断日志 | `Apm`, `ApmDispatcher`, `ApmDiagnostics`, `PersistentUploadWorker` |
| `apm-storage` | SQLite outbox 与 File 兼容存储 | `SQLiteEventStore`, `FileEventStore`, `PendingEventStore` |
| `apm-uploader` | HTTP/Logcat/自定义传输、逐请求 Header/endpoint 和非 durable 重试兼容路径 | `HttpApmUploader`, `HttpHeaderProvider`, `RetryingApmUploader` |
| `apm-remote-config` | HTTPS/ETag 拉取、Ed25519 验签、LKG、过期/回滚/同 revision 篡改保护 | `SignedRemoteConfigProvider`, `TinkEd25519SignatureVerifier` |

### 4.2 监控模块

| 模块 | 代码已经实现 | 必要接线与边界 |
|---|---|---|
| Memory | Heap/PSS/Native Heap、Activity/Fragment/ViewModel 泄漏、OOM、Hprof | 注册自动采样；ViewModel API 手动；dump 默认关闭 |
| Crash | Java crash、可选 Native signal、tombstone、ApplicationExitInfo | Java 默认；Native crash 默认关闭 |
| ANR | SIGQUIT flag、Watchdog、堆栈采样、traces、分类/去重 | 注册自动；Native 失败降级 Watchdog |
| Launch | 真实进程启动基线、冷/热/温、首帧、阶段 | Activity 自动；ContentProvider/App 阶段手动 |
| Network | OkHttp 全阶段、慢/错请求、聚合 | Interceptor/EventListener 或手动回调 |
| FPS | Choreographer 单调时间窗口、FrameMetrics 原始类型滚动累计、掉帧等级 | Activity 生命周期自动 |
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
| `apm-sample-app` | 集成 15 个监控模块的演示应用；实际演示 IO/SQLite/WebView/IPC/线程池/Battery 显式接线，并显式配置 `logcat://sample` 输出 |
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
- codec 硬上限 2 MiB；SQLite 默认 durable 单事件软上限 256 KiB
- 单字符串最大 1 MiB
- 单 Map 最大 4096 项
- 可逆保存事件结构，但 `fields` 中任意值会通过 `toString()` 归一为字符串

传输支持 Line Protocol 和零外部 runtime 依赖的 Protobuf writer；`eventId` 在 Line Protocol、Protobuf field 14、durable codec、SQLite 与多进程交接中保持稳定。服务端协议仍必须以该值实现幂等。

## 六、分发与宿主开销

`Apm.init` 会复制宿主持有的配置集合；`Apm.emit` 在调用线程捕获时间戳、线程名、业务上下文及顶层 fields/extras 快照，再把事件构建延迟到 dispatcher worker。宿主后续修改原 map 不会改写已发生事件，`bizContextProvider` 异常会记录 internal error 并降级为空上下文，不向业务调用栈外泄。队列容量 2048，producer 准入只做非等待 `tryLock` + `offer`；满载时高优先级事件可原子替换队列内最旧的最低优先级事件，同级保持先到先得。竞争时立即丢弃并计入自监控，业务线程不等待 worker 或其他 producer。

worker 单轮 drain 最多 32 条：

1. 解析 lazy event
2. 签名动态采样；按全局→模块→事件覆盖，ERROR/FATAL 绕过
3. 可选聚合
4. 动态 rate limit；按全局→模块→事件覆盖，ERROR/FATAL 绕过
5. PII 脱敏（默认启用，可显式关闭）
6. `appendBatch` 单事务落盘
7. 唤醒 persistent uploader

停止顺序：先切断新事件入口，再停止模块，排空 dispatcher，处理聚合残留，停止 uploader，关闭 store。排空均有超时上限。

## 七、持久化与上传

默认 `SQLiteEventStore`：

- schema v3，v2 通过 additive migration 保留所有 pending 行并补 `legacy-<rowId>`；WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启
- 默认容量同时受 50,000 行与 64 MiB 活跃 payload 逻辑预算约束；逻辑预算不等于 SQLite page/WAL 物理文件大小
- 默认单事件 durable payload 软上限 256 KiB；编码/超限失败只拒绝该事件，同批有效事件仍在一个事务中落盘
- 批量事务写入
- 缓存行数与 payload 字节，每 64 条成功 insert 重同步；跨 store 陈旧统计会在超限路径回读数据库真值
- `event_id` 唯一约束，本地重复追加幂等
- `lease_owner` + `lease_expires_at`；SQLite 写事务保证跨 store/进程 read-and-claim 原子性
- 高优先级先上传；超行数/字节预算时低优先级、旧事件先淘汰，拒绝和淘汰数量进入 SDK drop 健康计数
- 活动租约不被 prune/trim，因此预算可在租约释放/过期前临时超出；默认主进程单 writer 路径最接近严格逻辑水位
- 坏 payload 行隔离删除

`PersistentUploadWorker`：

- 每个实例生成唯一 owner，按 `uploadLeaseDurationMs` 原子 claim
- `BatchApmUploader` 一批一次请求；普通 uploader 逐条 fallback
- 成功后 owner-aware ACK；失败递增 retry 并释放；shutdown 主动释放 owner 全部 claim
- 指数退避与 `Retry-After` 取较大值
- 合并后的等待限制在 10 ms–60 s，负值或极端服务端 hint 不制造热循环/永久休眠
- `maxRetries` 表示首次尝试后的重试次数；失败计数达到 `maxRetries + 1` 后立即 prune，事件超过 7 天也会 prune
- owner 不匹配不能 ACK/失败修改；租约过期后其他 Worker 可重领

可靠性优先级是宿主安全、telemetry durability、diagnostic completeness。dispatcher 对单个 lazy factory/聚合/限流/脱敏的 recoverable `Exception` 单独降级，后续事件继续；fatal VM error 不转换成 drop。SQLite 的进程本地缓存计数在跨 store 删除后下限为 0，避免负缓存绕过容量淘汰。自定义同步 uploader 必须自行配置有界 IO，SDK 不尝试强杀任意宿主代码。

PII sanitization 默认开启。文本规则覆盖手机号、邮箱、身份证号和 URL 凭据；字段名保护会直接遮蔽 `authorization`、access/refresh token、password、API key、cookie、phone/email 等高置信字段，即使其值是数值或不符合文本正则。普通数值指标保持原类型；接入方仍需为自身业务字段补充自定义规则，不能把默认规则等同于完整合规证明。

`StorageType.FILE` 是 500 行 ring buffer 兼容路径，不提供成功确认、重启重放等 durable 语义，初始化会输出降级警告。

默认 HTTP uploader 支持静态协议身份 Header 和逐请求 `HttpHeaderProvider`。动态 provider 用于短期 Token，每次网络请求重新取值；provider 异常、Header 控制字符或覆盖 Content-Type/Content-Length/Host 等 transport Header 时请求失败，durable 行保留。`enableDynamicHttpEndpoint=true` 后才读取签名键 `apm.upload.endpoint`，且远程值只接受无 user-info 的 HTTPS URL；非法值或 provider 异常回退 APK 内置 endpoint。未配置或使用未知 scheme 时采用 payload-safe discard uploader：事件被确认丢弃以避免 outbox 无界增长，只输出一次不含 payload 的配置警告；开发期 Logcat 输出必须显式配置 `logcat://...`。

`apm-remote-config` 通过认证 GET `/v1/config` 拉取配置，发送 app/environment/installation 身份与 ETag。响应按服务端 canonical JSON 规则重建签名字节，并用 APK 固定的 32 字节原始 Ed25519 公钥通过 Tink 验签。只有验签、revision 单调、同 revision 签名一致且 app-private 缓存同步提交成功后才发布；204 主动停用，304 更新可信时间锚点，网络失败沿用未过期 LKG。最高 revision 即使配置过期或停用也不会回退。Android 平台原生 Ed25519 保证从 API 33 才开始，因此 minSdk 24 使用官方支持 Android 24+ 的 Tink 实现。

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

`SdkSelfMonitor` 每 60 秒生成 emit/drop/queue/upload latency/internal error 健康报告。AutoThrottle 在 drop rate > 50% 或平均上传延迟 > 10 秒时立即保持关闭 LOW 模块，drop rate > 80% 时扩大到指定 NORMAL 模块；恢复必须连续 3 个周期同时满足 drop rate <= 20% 且平均上传延迟 <= 3 秒。迟滞区间或再次退化会重置恢复计数，注册和动态配置也不能绕过当前自动降级集合；恢复时仍重新检查进程、签名动态配置和灰度门禁。

每份 `sdk_health` 先将仅含数值计数的摘要写入独立诊断 journal，再以 HIGH 优先级尝试普通事件管线。dispatcher 拥塞、采样或限流仍可能影响遥测副本，但不会抹掉独立本地摘要；诊断 sink 的 recoverable 失败也不能阻断事件尝试。

SDK 自诊断与普通 APM 事件是两个故障域：`ApmLogger` 继续输出 Logcat，同时把受控记录写入 200 条 / 4 MiB 内存环和按进程隔离的 app-private 滚动 JSONL；文件写入通过 256 条 / 4 MiB 非阻塞队列和 `apm-diagnostics-writer` 后台线程完成。每个进程默认保留 3 个 512 KiB 分片，磁盘预算约 1.5 MiB。

`ApmDiagnostics.status/snapshot/exportTo/clear` 及 `snapshotAsync/exportToAsync/clearAllProcesses` 支持现场状态、最近记录、聚合 ZIP 导出和明确范围的清理。每个 Android 进程拥有独立 journal 目录；内存环和写队列默认各有 4 MiB 字节预算，并保留原有条数预算。`status` 使用缓存资源计数，snapshot/导出读取文件时推荐异步 API。冷却期 writer 不提前出队，读/写故障独立计数；文件异常只更新本地状态并降级到内存 + 原始 Logcat，不重新进入 logger，避免递归。显式导出失败返回 `DiagnosticExportResult(success=false)`，自定义 store 的异常也不会逃逸。导出最多读取最近 16 个进程目录，合并结果受 10,000 条 / 16 MiB 双上限约束；目标不能覆盖活动 segment，manifest 带 SDK/process/session 与截断元数据。

结构化记录包含时间、级别、组件、错误码、进程、线程、异常类型、有限堆栈与栈指纹。消息最大 4 KiB，异常栈最大 16 KiB/64 帧，并脱敏常见 token/password/Authorization。事件 payload、业务上下文、请求正文、SQL 不进入诊断 journal。SDK 不自动上传诊断包。

## 十、配置默认值

| 配置 | 默认 | 说明 |
|---|---:|---|
| `storageType` | `SQLITE` | durable outbox |
| `maxEventPayloadBytes` | 262144 | 单事件 durable payload 软上限，超限单独拒绝 |
| `maxStoredPayloadBytes` | 67108864 | 活跃 payload 逻辑预算，不含 SQLite page/WAL 开销 |
| `endpoint` | 空 | 安全丢弃、不输出 payload；Logcat 需显式 `logcat://...` |
| `rateLimitEventsPerWindow` | 10/60s | 按 module/name 分桶 |
| `enableAggregation` | false | 高频 metric 与 alert 去重不默认启用 |
| `enablePiiSanitization` | true | 文本规则 + 高置信敏感字段名；关闭前需要隐私评审 |
| `debugLogging` | false | 默认关闭 SDK 调试日志 |
| `enableRetry` | true | durable 路径由 persistent worker 负责 |
| `uploadBatchSize` | 20 | 单次 durable batch 上限 |
| `uploadLeaseDurationMs` | 120000 ms | 一次上传 owner 租约 |
| `enableSelfMonitoring` | true | 60s 健康报告 |
| `enableAutoThrottle` | true | 异常时立即停模块，连续 3 个健康周期后恢复 |
| `diagnostics.enabled` | true | 独立本地诊断 journal |
| `enableMultiProcessCoordination` | false | 子进程 hand-off 关闭 |
| `enableHttpGzip` | true | 默认 endpoint HTTP 压缩 |
| `enableDynamicHttpEndpoint` | false | 远程 endpoint 覆盖默认关闭；开启后仍只接受 HTTPS |
| `httpHeaders` | 空 | 静态协议身份，不应包含长期密钥 |
| `httpHeaderProvider` | 空 | 每请求短期 Token/动态 Header |
| `IpcConfig.enableBinderHook` | false / deprecated | 无公共全局 Hook；使用显式 tracing |
| `WebviewConfig.enableAutoRegister` | false / deprecated | 无全局 WebView 注册；按实例 install |
| `ThreadMonitorConfig.enableThreadLeakDetect` | false / deprecated | 通用 leak 判断不可靠；显式注册线程池 |
| `RenderConfig.detectOverdraw` | false / deprecated | 公共 API 无 GPU overdraw 计数 |
| `IoConfig.enableAutoHook` | false / deprecated | 无公共全局 Java IO Hook；使用显式 stream wrapper |

各监控模块中历史遗留但没有对应运行时数据来源的 `maxStackTraceLength`（Battery/FPS/GC/Render）、Render 单 View `viewDrawThresholdMs` 和 Thread 通用 leak threshold 均保留为弃用兼容字段，不再描述为已生效能力。

## 十一、构建与发布

根构建统一 group/version、POM 元数据、sources JAR/AAR 和可选 signing。主构建、`apm-plugin`、`build-logic` 与独立 Maven consumer 均使用 Java 17 toolchain，同时允许 Gradle/AGP 支持的更新 JDK 作为 Gradle runtime；Android、纯 JVM、Gradle 插件与 consumer 的 Java/Kotlin 字节码目标统一为 17。`build-logic` 收敛发布型 Android library 的 compileSdk/minSdk/Java 版本；`apm-benchmark` 直接应用官方 Benchmark 插件并明确排除 Maven publication。`apm-plugin` 作为 included build 独立测试。

2026-07-16 在 JDK 17.0.14 执行的开发验证：

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat :apm-model:test --rerun-tasks --no-daemon
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

- root Gradle 94 个 JUnit XML 测试套件、595 个测试，0 failures / 0 errors / 0 skipped；included `apm-plugin` 另有 18 个测试通过；
- 23 份 `lint-results-debug.html`；
- `apm-sample-app-release-unsigned.apk`，4,708,872 字节；
- Maven Local 下当前 `com.apm:*-0.1.0` 发布包含 21 个 AAR、23 个 JAR、22 个 POM；
- `apm-benchmark` 未进入 Maven Local publication，Release 与 AndroidTest Kotlin 均编译成功；
- 独立 `smoke-tests/maven-consumer` 清理后重新解析本地制品并构建成功。

2026-07-21 的客户端 P0 hardening 在 JDK 17.0.14 下通过 `:apm-core:testDebugUnitTest --no-daemon`：23 个 suite、153 个测试、0 failures/errors/skips；`python docs/verify_docs.py` 通过 40 个 Markdown 文件和 37 个本地链接。根 `testDebugUnitTest` 复验曾启动，但运行中共享 Gradle 8.13 distribution/transform cache 被外部清理，Kotlin daemon 因缺失 `jsr305-3_0_2_jar-snapshot.bin` 失败，wrapper 随后联网重下也超时；因此这次不新增“根测试通过”结论，最近一次完整根基线仍是上面的 2026-07-16 / 595 tests。

2026-07-22 的第二批 hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest :apm-storage:testDebugUnitTest --no-daemon`：core 23 suites / 160 tests，storage 6 suites / 36 tests，均为 0 failures/errors/skips；storage 在分块 trim 加固后另以 `--rerun-tasks` 完整重跑 36 个测试并通过。`python docs/verify_docs.py` 再次通过 40 个 Markdown 文件和 37 个本地链接。这是 AutoThrottle 恢复与 durable payload 预算的定向增量基线，不替代 2026-07-16 的 595-test 全根基线。

2026-07-22 的第三批 measurement/self-observability hardening 在 JDK 17.0.14 下以 `--rerun-tasks` 强制重跑：FPS 4 suites / 31 tests，core 23 suites / 162 tests，均为 0 failures/errors/skips。补入 API 26 delayed-frame 门禁后又执行 `:apm-fps:testDebugUnitTest :apm-fps:lintDebug --no-daemon` 并通过；当前 `apm-fps` 与 `apm-core` lint 文本报告均为 `No issues found`，`python docs/verify_docs.py` 通过 40 个 Markdown 文件和 37 个本地链接。该定向基线覆盖 FPS 单调时间窗口、FrameMetrics primitive rolling accumulator、API 26 门禁和 `sdk_health` 独立摘要/HIGH 事件双通道，不替代 2026-07-16 的全根结果。

`apm-model`、`apm-core`、`apm-plugin` 与 sample 的代表性 class 文件均由 `javap -verbose` 确认为 major version 61，即 Java 17 字节码。同日另用 JDK 21.0.11 启动 Gradle，根构建配置与 `:apm-model:test` 成功，生成的 model class 仍为 major version 61。

设备侧同日验证：ADB 可见 Xiaomi `22041216UC` 与 Android 17 emulator。物理机在安装 benchmark APK 时被设备安全策略以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，因此未产生物理性能数值；emulator 在显式抑制 AndroidX 的 `EMULATOR` 环境门禁后，`encodeDurableEvent`、`decodeDurableEvent`、`appendDispatcherBatch` 三个方法均完成并生成 benchmark JSON/Perfetto，但 runner 最终因 `IsolationActivity` 45 秒启动超时将任务标记失败。模拟器结果只证明 instrumentation 执行链，不作为真机性能结论。

仓库没有外部 Maven 发布凭据或已完成的 Maven Central 发布；`publishToMavenLocal` 成功不代表外部仓库已发布。

## 十二、测试策略

93 个测试/benchmark 文件覆盖配置默认值、事件 identity/codec/Protobuf、dispatcher 单事件故障隔离/fatal 边界、签名配置 canonical JSON/Ed25519/HTTP/ETag/LKG/过期/rollback/equivocation、动态 kill switch/采样/限流/endpoint/短期 Header、PII、聚合/指纹、durable outbox migration/lease/concurrency/固定种子状态机、GC 分配/回收窗口、IO 吞吐窗口、SQLite QueryPlan gate/现代 SCAN 解析、IPC 文件、SDK 诊断脱敏/JSONL/滚动/导出失败数据化/并发降级、Provider 自动初始化/no-op/错误隔离、Memory Reporter/OOM/Hprof 截断输入/ViewModel 引用/真实采样、Network 请求分类/聚合/phase 截断、JNI 静态绑定契约、ASM 正常/异常出口、Binder/线程池/WebView、FPS 单调时间窗口与 FrameMetrics primitive rolling accumulator 核心计算，以及两个真机 Microbenchmark 入口。

测试通过不能代替以下验证：

- 真机 Native 行为与符号化
- 进程被杀/断电/磁盘满场景
- 长时间功耗与内存开销
- 多 OEM/Android 版本兼容
- 真实 Collector 协议与服务端幂等

## 十三、客户端完成边界与外部工作

仓库内可完成的客户端缺口已收口：稳定事件身份、SQLite v3 additive migration、本地去重、claim/lease/expiry、owner-aware ACK、单事件/总量 payload 预算、逐请求短期鉴权、签名配置/LKG/kill switch/采样/限流/endpoint、优先级感知入口背压、带迟滞恢复的 AutoThrottle、默认隐私保护、运行时配置/payload 快照、显式 Binder/WebView/线程池公共 API、FPS 单调时间窗口、FrameMetrics 无逐帧对象分配滚动累计、`sdk_health` 双通道、自诊断和 benchmark harness 均已实现。手动与 Provider 自动初始化现在有明确互斥文档和生命周期测试；sample 对 IO、SQLite、WebView、IPC、线程池与 Battery 使用真实显式 API，而不只注册模块。

一个保留的兼容边界是 `fields` 任意值在 durable round-trip 后归一为字符串；改变它需要版本化 typed-field wire schema，会影响 Collector，纳入云端协议共同设计而不是静默改格式。

生产 Collector、鉴权/租户、服务端幂等、查询聚合/告警/Dashboard、Native 后台符号化、外部 Maven 发布、云端 CI，以及真机 soak/功耗/热/磁盘数值全部依赖外部系统或设备。唯一任务清单和验收条件由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 维护。

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
