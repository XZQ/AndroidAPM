# apm-core 模块架构

> 同步日期：2026-07-22

## 1. 职责

`apm-core` 是 SDK 控制面和数据面入口：

- `Apm`：全局初始化、模块注册/停止、事件发射
- `ApmRuntimeProfile` / `CollectionConsent`：初始化前生产约束与 sticky 撤回门禁
- `ApmInitProvider`：可选 manifest metadata 自动初始化；无 metadata 时 no-op
- `ApmContext`：向模块暴露 Application/config/logger/emit
- `ApmDispatcher`：有界异步管线
- `PersistentUploadWorker`：durable outbox 单 worker 回放
- `ProcessEventCoordinator`：可选多进程文件 hand-off
- `UploaderFactory`：选择 custom/HTTP/显式 Logcat/payload-safe discard 与 durable/non-durable 重试所有权
- throttle/aggregation/privacy/selfmonitor：签名动态采样/限流与横切保护能力
- `ApmExecutors`：SDK 线程工厂和优先级策略
- `ApmDiagnostics`：独立本地诊断状态、快照、ZIP 导出和清理

## 2. 初始化

```text
Apm.init(application, config)
  synchronized(initLock)
  -> ignore duplicate init
  -> reject sticky revocation / validate runtime profile and consent
  -> snapshot collection-valued runtime config
  -> resolve processName
  -> create independent diagnostics recorder/logger
  -> MAIN_PROCESS_ONLY child process: skip
  -> create EventStore
       SQLITE -> SQLiteEventStore(EventDbHelper)
       FILE   -> FileEventStore + durability warning
  -> UploaderFactory.create(config, durableStore)
  -> optional RateLimiter
  -> optional EventAggregator
  -> default PiiSanitizer unless explicitly disabled
  -> optional SdkSelfMonitor
  -> ApmDispatcher
  -> optional ProcessEventCoordinator
  -> ApmContext / State
  -> start ManagedDynamicConfigProvider
  -> start registered modules
```

`state` 只有基础设施组装完成后才发布。runtime profile/consent 在 diagnostics、event store 和线程之前校验：`DENIED` 总是拒绝；`PRODUCTION_STRICT` 还要求显式 `GRANTED`、SQLite、PII on、debug off，以及 HTTPS endpoint 或非 Logcat custom uploader。strict built-in HTTP 进一步要求 `PROTOBUF_ENVELOPE_V2`、四项完整有界的 standard resource、64 KiB–4 MiB batch budget，并给 `maxEventPayloadBytes` 至少保留 16 KiB envelope headroom。校验通过后初始化复制 `customProcessModules`、`defaultContext`、自定义脱敏规则列表和静态 HTTP Header，宿主后续修改原集合不会改变运行时。diagnostics 在 event store/uploader 之前创建，因此其后的部分初始化失败仍可导出本地证据。重复 `init` 为 no-op；普通 `stop` 后可以重新初始化，consent revoke 后必须先显式 grant。

初始化模式必须二选一：

- 手动模式由宿主 `Application` 调用 `Apm.init`，并用 manifest merger 的 `tools:node="remove"` 移除 `ApmInitProvider`；sample 采用此模式。
- 自动模式保留 `ApmInitProvider`，通过 `com.apm.config_class` metadata 反射创建无参 `ApmConfigProvider`，在 `Application.onCreate` 前调用 `Apm.init`。

缺少 metadata 是受支持的手动/未配置状态，只记录 debug 日志；无效类、配置构造或初始化异常会被 Provider 隔离，不阻断宿主启动。Robolectric 生命周期测试覆盖 metadata 缺失、有效 provider 和无效 provider 三条路径。

## 3. 模块生命周期与过滤

`register` 在 `init` 前后都可调用，模块名去重检查与插入由同一个 `initLock` 保护。

`startModule` 顺序：

1. 已启动同名模块：跳过
2. `ProcessModuleFilter`：MAIN/ALL/CUSTOM
3. 全局 `apm.enabled`
4. `dynamicConfigProvider.getBoolean("apm.module.<name>.enabled")`
5. `GrayReleaseController.isEnabled("module.<name>")`
6. `onInitialize(context)`
7. `onStart()`
8. 加入 `startedModules`

启动异常被记录，不向宿主抛出。`stop` 只停止真正启动成功的模块。若 provider 实现 `ManagedDynamicConfigProvider`，core 在状态发布后启动轮询并接收“可信视图已变化”回调；回调与 register/init/stop 共用 `initLock`，会动态停止或恢复模块。provider 在模块和 dispatcher 之前停止，避免关闭过程中继续触发重配。

## 4. emit 路径

`Apm.emit` 在调用线程执行：

- 读取当前 state；未初始化直接返回
- 捕获 `System.currentTimeMillis()`
- 捕获 `Thread.currentThread().name`
- 按 `BizContextCaptureMode` 现场调用 provider 或 O(1) 读取异步 LKG，并取得不可变快照
- 复制顶层 `fields` / `extras`，冻结本次发生时刻的 payload map
- 用 `ApmEventSizeEstimator` 对已冻结字符串、标量和 map 做保守 retained-memory 估算，不执行 durable 编码
- 创建 lazy event factory 并非阻塞入队

event 的 map 合并和对象构建延迟到 dispatcher worker。宿主 context provider 的运行时异常不会传播到业务路径。子进程 IPC 需要完整 payload，因此会立即执行 factory。

### 业务上下文延迟边界

`SYNCHRONOUS` 是兼容默认值：每次 emit 在调用线程取得精确事件时刻快照，因此 provider 契约必须是 O(1)、无 IO、无等待锁。把 provider 直接延迟到 dispatcher 会读取“处理时刻”而不是“发生时刻”的账号/租户状态，不能作为无损优化。

`ASYNC_CACHED` 用 `BizContextSnapshotSource` 在 `apm-biz-context` 单线程 executor 立即异步首刷并按配置周期刷新。provider 返回值复制后通过 `AtomicReference` 发布；失败只记录 `biz_context_provider` internal error，保留 last-known-good，不用空值覆盖。emit 只读取原子快照，不执行宿主代码；代价是首次成功前为空、正常最多滞后一个刷新周期。`bizContextRefreshIntervalMs` 运行时约束到 100 ms–24 h；`Apm.refreshBizContext()` 可在登录/退出/租户切换后请求即时后台刷新，`AtomicBoolean` 最多允许一个显式 pending 任务，避免请求风暴形成无界队列。stop 和 init rollback 都会关闭该 executor。

`emitCriticalSync` 不走 lazy queue：调用方较低 priority 自动提升为 CRITICAL，立即构建、脱敏、同步 append 或同步 IPC publish，返回是否到达本地 hand-off point。Crash/ANR 均使用该入口；它绕过 sampling/aggregation/rate limit 且不执行网络请求。非 uploader 进程同步 IPC 失败会把 `IPC_FILE_BYTE_BUDGET` / `IPC_DIRECTORY_BYTE_BUDGET` 等准确 reason 与 CRITICAL priority 计数一次；无法细分的发布失败才使用 `IPC_HANDOFF_FAILURE`。上传进程扫描 CRITICAL `.ipc` 后调用 `dispatchCriticalSync`，不重新进入普通队列；只有同步存储接受后才删除文件。false、recoverable 存储异常或回调未就绪会保留整文件，下次扫描按 at-least-once 重试；fatal VM error 不被吞掉。

## 5. Dispatcher

### 队列与 worker

- `ArrayBlockingQueue<QueuedEvent>`
- capacity：2048 events + 默认 8 MiB estimated retained bytes
- producer：`tryLock` + `offer`，永不等待；锁竞争时立即 drop + self-monitor
- 默认 noisy-neighbor 门禁：队列达到 75% 后，同一来源模块若已占总容量 50%，后续 NORMAL/LOW 立即 drop；HIGH/CRITICAL 绕过
- 每个排队模块使用 O(1) 占用计数；offer 前登记，offer 失败/优先级淘汰/worker drain 时释放，空模块键删除
- `queuedBytes` 在 admission lock 下随 offer/drain/淘汰同步更新，并暴露给 `sdk_health`
- overflow：高优先级可替换足够数量的最旧最低优先级事件，使条数和字节同时满足；同级先到先得
- 单事件估算超过总预算时以 `DISPATCHER_BYTE_BUDGET` 立即拒绝，不进入队列
- admission lock 同时覆盖 remove + offer，避免替换空位被并发 producer 抢占
- worker poll：100 ms
- 每轮：首条 + `drainTo`，最多 32 条
- shutdown：不再接收，继续排空已接受事件，最多等待 3 秒

公共配置为 `maxDispatcherQueueBytes=8388608`、`enableDispatcherModuleIsolation=true`、`dispatcherIsolationHighWatermarkPercent=75`、`dispatcherMaxModuleQueueSharePercent=50`；两个百分比运行时约束到 1–100，单模块占比不会高于高水位。关闭模块隔离时不维护模块占用 map，但条数/字节总预算仍生效。该门禁只隔离共享入口容量，worker 仍单线程顺序执行以下 pipeline；它不构成多 worker 或按模块并行化声明。

满队列或字节压力下的高优先级替换先用一次无分配扫描证明低优先级条数/字节足够，再按固定 LOW→NORMAL→HIGH 优先级做最多三次 FIFO 扫描，同优先级保持 oldest-first。选择阶段只分配最终 victim list，确认条数/字节都能满足后才原子 remove + offer；不再把最多 2,048 个低优先级候选复制后排序。`apm-benchmark` 通过 32 次常态准入与满队列 HIGH 准入两个公共 API AndroidX 方法保护这条 producer path；当前源码已编译，新增两项仍待物理设备生成完整 time/allocation JSON。

### 批处理顺序

```text
resolve lazy event
  -> DynamicEventPolicy.sample (global -> module -> event)
  -> EventAggregator.process (when enabled and not already aggregated)
  -> DynamicEventPolicy.rateLimit + RateLimiter.tryAcquire
       ERROR/FATAL bypass
  -> PiiSanitizer.sanitize (when enabled)
  -> EventStore.appendBatch
  -> durable store: PersistentUploadWorker.signal
     non-durable store: uploader.upload each event
```

单个 queued event 的 lazy factory、聚合、限流或脱敏出现 recoverable `Exception` 时只丢弃该事件并记录 internal error，后续事件继续；批量存储的 recoverable 异常会把整批计入 drop，但不会让 worker 退出。`VirtualMachineError` 等 fatal VM error 不转换为 drop。

默认关闭聚合或处理 pre-aggregated 事件时，worker 直接把单个 resolved event 送入限流/脱敏，不再为每条事件创建 `listOf(event)`；非 durable store 在存储没有拒绝项时也不再创建空的 rejected-id `HashSet`。只有真实聚合扩展或真实拒绝集合才承担对应 collection 分配。

启用 self-monitor 时，worker 用单调纳秒测量 `resolve`、`sampling`、`aggregate`、`rateLimit`、`sanitize` 和 `storeHandoff`。每阶段维护固定 22 桶直方图及 count/sum/max，记录路径无逐样本对象分配；周期 snapshot 通过短同步区间取得一致 count、向上取整平均微秒、保守 P95 桶上界和最大微秒后清零。`storeHandoff` 是 batch append，因此其 count 与按 event/expanded-event 运行的其他阶段不应直接比较；聚合/脱敏禁用或 pre-aggregated bypass 时相应阶段可以为零。关闭 self-monitor 时计时 helper 直接执行原 block，不读取单调时钟。这些字段不改变 worker 顺序、采样、限流或 AutoThrottle 决策。

### 聚合维护

启用聚合时，`apm-aggregation` 定期 flush 过期窗口。聚合输出以 `preAggregated` 标记重新入队，避免二次聚合。关闭时剩余聚合数据直接写 store/交 uploader。

## 6. PersistentUploadWorker

worker 随 `PendingEventStore` 创建并立即开始循环：

```text
claimPending(ownerId, batchSize, now, leaseDuration)
  empty -> pruneExpired -> wait signal or 30s
  non-empty -> upload once
      BatchApmUploader: one batch call
      ordinary uploader: all(event.upload)
    success -> acknowledgeClaim(ownerId, ids) + record latency
    failure -> failClaim(ownerId, ids) and release ownership
            -> clamp(max(local backoff, Retry-After), 10 ms, 60 s)
            -> wait and reselect
```

单一重试权威位于 outbox worker；durable store 下 `UploaderFactory` 不再套 `RetryingApmUploader`，避免双队列/双重试。`RetryPolicy.maxRetries` 表示首次尝试之后允许的次数；每次失败 owner-aware 释放并递增计数，达到 `maxRetries + 1` 次失败时立即 prune，不再依赖“等到 outbox 为空”才清理。

每个 Worker 使用 `ProcessSessionId + process-local sequence` 作为 owner。SQLite 在写事务中选择并持久化 claim，另一个 store/进程看不到活动租约；只有 owner 可 ACK 或失败释放，shutdown 释放其全部 claim，expiry 后其他 Worker 可重领。默认 `uploadLeaseDurationMs=120000`。

claim/count/ACK/fail/prune/upload 的 recoverable `Exception` 在 worker 内降级；fatal VM error 不被 `runCatching` 吞掉。自定义同步 uploader 若忽略 interrupt，SDK 只能在 3 秒后结束等待并依赖 lease expiry 让其他 Worker 恢复，无法安全终止任意宿主代码。

## 7. UploaderFactory

选择顺序：

1. `config.uploader`
2. `http://` / `https://` endpoint -> `HttpApmUploader`
3. 显式 `logcat://` endpoint -> `LogcatApmUploader`
4. 其他/空 endpoint -> payload-safe discard uploader

该选择顺序属于 compatibility 行为。strict profile 在进入 factory 前拒绝空/HTTP/Logcat endpoint，除非 `config.uploader` 是显式非 Logcat 实现；built-in HTTP 同时要求 V2/resource/byte budget。factory 把固定 resource 和 `maxUploadBatchBytes` 传给 `HttpApmUploader`。非 durable store 且 `enableRetry=true` 时，外层使用 `RetryingApmUploader`。durable store 自己负责持久重试。discard uploader 返回成功以确认并清理本地事件，避免错误配置导致 outbox 无界增长；它只输出一次不含事件 payload 的配置警告。

默认 HTTP uploader 同时接收 `httpHeaders` 和每请求执行的 `HttpHeaderProvider`，动态项覆盖同名静态项；长期密钥不得放入 APK 静态 map。`enableDynamicHttpEndpoint=true` 时，factory 把动态键 `apm.upload.endpoint` 桥接到 uploader；远程值必须是无 user-info 的 HTTPS URL，否则保留 bootstrap endpoint。

## 8. 多进程协调

`enableMultiProcessCoordination=false` 是默认值。

开启后：

- main process：dispatcher + scan executor
- child process：write executor，不直接调用 uploader；普通 producer 通过 lock-free queue + 原子 4 MiB 估算字节预算准入，不等待 writer IO
- 普通事件：唯一 500 ms fixed-delay task 取出 pending，单轮最多 16,384 条并按 100 行/文件字节拆分；不为每个事件提交 executor task
- critical：同步单文件，并返回准确预算/发布失败原因
- payload：`ApmEventCodec` 后 Base64，每行一个事件
- raw event：默认最大 256 KiB
- publish：单 ready 文件默认最大 1 MiB；unique `.tmp` -> 在跨进程锁内检查 16 MiB ready-directory 预算 -> `.ipc`
- scan：5 秒
- published ready `.ipc`：下游接受后删除，不按年龄先行删除
- incomplete `.tmp` age limit：5 分钟

主进程先按 ready 文件实际大小拒绝超限文件，再流式逐行检查行数/行长/解码 payload，避免 `readLines()` 构造无界 List；decode 后添加 `extras["ipc_source"]="remote_process"`。CRITICAL 直接同步到 store，普通事件保持异步 dispatcher 兼容路径。完整文件只有在所有可解码事件都被下游接受后才删除；某一行失败会保留整文件，因此重试可能重复前序事件，SQLite 的唯一 `eventId` 和 Collector 幂等共同承担去重。容量拒绝分别记录 `IPC_PENDING_BYTE_BUDGET`、`IPC_FILE_BYTE_BUDGET` 或 `IPC_DIRECTORY_BYTE_BUDGET`。

## 9. 限流、灰度与动态配置

`RateLimiter`：

- key：`module/name`
- 默认 10 events / 60 seconds
- ERROR/FATAL bypass
- bucket map 维护 LRU，上限 256

`GrayReleaseController` 通过稳定 userId/sample rate 和 override 决定模块是否启用。`DynamicConfigProvider` 是接口，core 不绑定具体网络实现；`apm-remote-config` 提供生产实现。

`DynamicEventPolicy` 在 dispatcher worker 上读取签名快照，避免给调用线程增加 IO。采样使用 eventId 稳定 hash，basis points 限制为 0–10000；限流容量限制为 0–1,000,000，窗口限制为 1 秒–24 小时。键按默认、模块、事件逐级覆盖：

- `apm.sampling.default_basis_points`
- `apm.sampling.<module>[.<event>].basis_points`
- `apm.rate_limit.default_events_per_window` / `default_window_ms`
- `apm.rate_limit.<module>[.<event>].events_per_window` / `window_ms`

ERROR/FATAL 绕过动态采样和限流。provider 读取异常通过 internal error 记录并回退上一级/本地值，不让自定义控制面破坏宿主。

## 10. 聚合与隐私

聚合默认关闭：

- METRIC：窗口统计与有限样本
- ALERT：stack fingerprint 去重
- bucket/sample/cache 都有硬上限

PII sanitization 默认开启。内置文本规则覆盖手机号、邮箱、身份证、URL token/password；字段名保护直接遮蔽 authorization、auth/authentication/auth-header、password、access/refresh token、API key、cookie、phone/email 等高置信字段，因此数值型直接标识符也不会绕过。字段名先移除分隔符并统一大小写，`author` 等非敏感近似名不误伤；内置 Regex 预编译一次。固定种子语料覆盖分隔符/大小写变体、混合手机号/邮箱/token 以及原事件不可变。普通数值指标保持原类型，生产环境仍需结合自身字段和法规扩展规则。compatibility 可显式关闭但必须完成隐私评审；strict 禁止关闭。

## 11. 自监控与降级

`SdkSelfMonitor` 记录：

- emit count
- drop count/rate
- dispatcher module-isolation drop count（同时计入总 drop）
- queue size
- queue estimated bytes
- dispatcher fixed-stage count/average/P95-upper-bound/max latency
- average/max upload latency
- internal error count

`Apm.recordInternalError` 为模块吞掉并降级的异常提供统一计数和带稳定错误码、异常类型、有限堆栈的本地记录。`sdk_health` 字段包含 `internalErrorCount`、`dispatcherModuleIsolationDropCount`、`queueBytes`、`diagnosticDroppedCount` 和 `diagnosticWriteFailureCount`，并把固定 `SdkDropReason` 与 LOW/NORMAL/HIGH/CRITICAL 计数展开为 `dropReason.*` / `dropPriority.*` 数值字段。固定阶段延迟以 `dispatcherStage.<stage>.count/avgMicros/p95UpperBoundMicros/maxMicros` 展开；P95 名称明确表明它是直方图上界，不伪装成保存原始样本后的精确分位数。队列竞争/满/字节预算/优先级淘汰/模块隔离、处理失败、采样、限流、storage reject/failure/evict、non-durable uploader reject、outbox prune、consent erase，以及 IPC pending/file/directory budget/critical failure 均有明确 reason；兼容层只返回总数时进入 `dropPriority.unattributed`。每份 health report 先写一条不含业务 payload 的独立诊断摘要，再以 HIGH 优先级尝试普通事件管线；诊断 sink 的 recoverable 失败不能阻断事件尝试，也不能递归自报错。

`AutoThrottleController` 根据 drop rate/upload latency 维护完整降级集合：drop rate > 50% 或平均上传延迟 > 10 秒立即关闭 LOW 模块，drop rate > 80% 时扩大到指定 NORMAL 模块。恢复阈值采用迟滞：连续 3 个周期 drop rate <= 20% 且平均上传延迟 <= 3 秒才释放；迟滞区间或再次退化会重置计数。`Apm` 在 `initLock` 下镜像该集合，后注册和动态配置不能绕过；恢复仍通过 `startModule` 重查进程、签名配置和灰度门禁。健康事件的 telemetry 副本仍经过同一 dispatcher，但 HIGH 优先级可在入口满载时替换更低优先级事件。

独立诊断默认使用 200 条 / 4 MiB 内存环、256 条 / 4 MiB 非阻塞写队列，以及每进程 3 × 512 KiB app-private JSONL。普通日志调用线程不做文件 IO；ERROR 只可挤出较旧的非 ERROR 排队记录并计入 drop。文件失败进入冷却时 writer 在出队前等待，已接受记录不会被静默结算；读/写失败独立计数，`status()` 使用缓存磁盘字节且不遍历文件。文件异常不通过 `ApmLogger` 递归报告。

进程名安全前缀 + SHA-256 前缀形成稳定独立目录，避免子进程与主进程轮转同一文件。`exportTo` 聚合最近最多 16 个进程 journal、合并当前内存证据，并以 10,000 条 / 16 MiB 双上限约束未压缩 JSONL；目标不能覆盖任一源 segment，manifest 包含格式/SDK/session/process 及截断元数据。自定义 diagnostic store 导出异常也转换为 `DiagnosticExportResult(success=false)`，不逃逸到宿主。`snapshot/exportTo` 同步兼容接口要求工作线程，宿主应优先使用 `snapshotAsync/exportToAsync`；`clear` 仅清当前进程，`clearAllProcesses` 为显式跨进程清理。

`HostIntegrationRegistry` 是独立于 journal 的固定大小进程内状态：7 个枚举槽分别使用原子 module/registration/observation/time 字段，不保留宿主对象或业务值。监控模块通过 `ApmContext` 的 synthetic 跨制品 SPI 更新状态，宿主只从 `ApmDiagnostics.hostIntegrationSnapshot()` 读取不可变结果。状态机明确区分 `MODULE_INACTIVE`、运行但尚无证据、当前 registration、已观察调用，以及 registration + 调用同时存在；因此低流量场景不会被伪判为接入失败。`Apm.init` 在通过配置校验后开始新会话并清除旧证据，初始化回滚/跳过进程/`stop` 停止后续更新并清零活跃 registration；已停止会话的观察计数仅用于本地支持查看，下一次 init 才清除。

初始化资源先保存在局部 staged 状态，全部成功后才发布；失败时按 scheduler → coordinator → dispatcher（或 uploader/store）逆序回滚。停止阶段隔离各模块与基础设施异常，独立诊断最后关闭。`ApmLogger.withComponent` 为 uploader、dispatcher、aggregation、privacy 和具体监控模块保留真实归属。`sdk_health` 中诊断 drop/write failure 使用区间增量而非累计值。

## 12. ApmExecutors

- 统一 `apm-` 线程名前缀
- daemon thread
- `PRIORITY_BACKGROUND = MIN_PRIORITY`
- `PRIORITY_MEASUREMENT = NORM_PRIORITY`
- single-thread 与 scheduled executor 工厂

core/监控模块应使用该设施。`apm-uploader` 是下层模块，不能反向依赖 core，因此保留本地 executor。

## 13. Shutdown

1. `state=null`，切断新 emit
2. stop managed dynamic config
3. stop self-monitor/coordinator
4. `module.onStop`
5. dispatcher drain（3 秒）
6. flush aggregator residue
7. persistent worker shutdown 或 uploader shutdown
8. store close

这是有界优雅关闭，不承诺在系统强杀时运行；关键事件依赖同步 local hand-off 与下次进程重放。

Consent revocation 使用独立顺序：先在 `initLock` 下设置 sticky gate 并清空 `state`，再停止配置/业务上下文/自监控/IPC producer 与模块；dispatcher queue 直接丢弃并计入 drop，不 drain，也不 flush aggregation；persistent worker/uploader 结束后才 clear/close store。带 `Application` 的 overload 会在 runtime 已停止或冷启动时重新打开并清理 SQLite/File 路径，同时删除 IPC `.ipc/.tmp`。结果对象区分实际清理、未知计数和无法定位；重新同意需要 `grantCollectionConsent()` + `init()`。该 gate 是进程本地状态，多进程宿主负责把撤回通知送达每个 SDK 进程。

## 14. 测试重点

- `ApmConfigTest`, `ApmConsentLifecycleTest`, `UploaderFactoryTest`
- `ApmDispatcherTest`
- dispatcher stage histogram/P95/reset/health-field consistency
- `PersistentUploadWorkerTest`
- `ProcessEventCoordinatorTest`
- RateLimiter/Gray/DynamicConfig
- managed kill switch、dynamic sampling/rate-limit、dynamic endpoint wiring
- strict V2/resource/batch-byte 配置门禁与 uploader factory wiring
- Aggregator/StackFingerprinter
- PII sanitizer
- SDK self-monitor
- diagnostics config/sanitizer/JSONL/rotation/export/queue/failure isolation/lifecycle integration
- `ApmInitProvider` no-op、成功初始化与错误隔离生命周期

## 15. 已知限制

- 客户端具备 eventId 和 concurrent outbox lease，但 exactly-once 仍依赖服务端按 eventId 幂等
- self-monitor health event 不是独立控制平面；详细错误由独立本地 journal 补足
- diagnostics 默认不自动上传，分享流程由宿主显式控制
- 公共 API 无法实现的通用隐藏 Hook 配置保留为 deprecated/false；真实能力使用显式 API
- compatibility 保持源兼容；生产必须显式选择 strict 并声明 consent
- consent gate 是进程本地；多进程撤回需要宿主传播，磁盘路径为 app-private shared artifacts
- PII 默认开启；aggregation/multi-process 默认关闭，生产配置仍需明确评审

## 时间与快照语义

`ApmClock` 把 epoch timestamp 与单调 elapsed measurement 分开：事件/持久化协议继续输出 epoch，dispatcher/upload latency、诊断冷却、限流、指纹去重和聚合过期使用单调时间并把负 duration 归零。公共 `emit` 与直接事件入口都在进入异步队列或 IPC 协调器前复制 fields/globalContext/extras。
