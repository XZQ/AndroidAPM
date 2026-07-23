# Android APM 项目文档

> 文档同步：2026-07-23｜27 个构建单元｜164 个主源码文件（159 Kotlin + 4 C + 1 proto）｜102 个测试/benchmark 文件

## 一、项目结论

AndroidAPM 是模块化 Android 端 APM SDK。它覆盖采集、统一事件、开销保护、持久化、跨进程交接、动态短期鉴权、签名远程配置和上传；生产 Collector、查询聚合、告警、Dashboard、Native 符号化及托管服务不在仓库内。

当前最成熟的部分不是单个监控算法，而是所有模块共享的可靠事件管线：

```text
monitor module
  -> Apm.emit
  -> priority-aware bounded queue (2048 events / 8 MiB estimated retained bytes; default 75% high-water / 50% per-module NORMAL/LOW share)
  -> signed dynamic sampling / optional aggregation / dynamic rate limit / default PII sanitization
  -> appendBatch (up to 32)
  -> SQLite durable outbox v3 (50,000 rows / 64 MiB live payload, 256 KiB per event, unique eventId)
  -> claim(owner, lease, expiry) -> PersistentUploadWorker
  -> batch/custom uploader
  -> integrator-owned collector
```

Crash/ANR 关键事件通过 `Apm.emitCriticalSync` 绕过共享队列、采样、聚合与限流，同步到 SQLite 或 critical IPC hand-off point；较低调用方 priority 自动提升为 CRITICAL，返回成功前不执行网络 IO。每个事件拥有稳定 `eventId`，上传 Worker 原子 claim 后由 owner ACK/失败释放，租约过期可重领。上传成功后删除，失败保留并重试；这是 at-least-once，不是 exactly-once，网络响应不确定时服务端仍须按 `eventId` 去重。

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
8. Collector 接入再读 `docs/protocol/COLLECTOR_WIRE_V2.md`

`docs/` 属于项目交付并纳入 Git；`.workbuddy/`、`.github/`、`.claude/` 是本地状态，保持忽略。

## 三、工程基线

| 项目 | 当前值 |
|---|---|
| 分支 | `develop` |
| runtime tip | 使用 `git log --oneline -n 10` 查看；远程配置实现与本文档同一交付提交 |
| root Gradle subproject | 25 |
| included build | 2：`apm-plugin`、`build-logic` |
| 总构建单元 | 27 |
| 主源码 | 164：159 Kotlin + 4 C + 1 proto |
| 测试/benchmark 文件 | 102 |
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
| `apm-model` | 统一事件、优先级、legacy wire、V2 typed batch envelope、持久化 codec | `ApmEvent`, `ApmEventCodec`, `ProtobufSerializer`, `ApmBatchEnvelopeSerializer` |
| `apm-core` | 初始化、生命周期、分发、限流、聚合、脱敏、多进程、自监控、独立诊断日志 | `Apm`, `ApmDispatcher`, `ApmDiagnostics`, `PersistentUploadWorker` |
| `apm-storage` | SQLite outbox 与 File 兼容存储 | `SQLiteEventStore`, `FileEventStore`, `PendingEventStore` |
| `apm-uploader` | HTTP/Logcat/自定义传输、逐请求 Header/endpoint 和非 durable 重试兼容路径 | `HttpApmUploader`, `HttpHeaderProvider`, `RetryingApmUploader` |
| `apm-remote-config` | HTTPS/ETag 拉取、Ed25519 验签、LKG、过期/回滚/同 revision 篡改保护 | `SignedRemoteConfigProvider`, `TinkEd25519SignatureVerifier` |

### 4.2 监控模块

| 模块 | 代码已经实现 | 必要接线与边界 |
|---|---|---|
| Memory | Heap/PSS/Native Heap、Activity/Fragment/ViewModel 泄漏、OOM、Hprof | 注册自动采样；ViewModel API 手动；dump 默认关闭 |
| Crash | Java crash、可选 Native signal、tombstone、ApplicationExitInfo | Java 默认；Native crash 默认关闭 |
| ANR | SIGQUIT flag、Watchdog、堆栈采样、traces、分类/去重、同步 critical hand-off | 注册自动；Native 失败降级 Watchdog |
| Launch | 真实进程启动基线、冷/热/温、首帧、阶段 | Activity 自动；ContentProvider/App 阶段手动 |
| Network | OkHttp 全阶段、HttpURLConnection 总耗时、慢/错请求、聚合 | Interceptor/EventListener、显式 `traceHttpUrlConnection` 或手动回调 |
| FPS | API 24+ FrameMetrics 真实渲染事件单调窗口、按实际区间计算并按刷新率封顶、原始类型滚动累计；失败时回退 Choreographer | Activity 生命周期自动 |
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
| `apm-bundle` | 单依赖完整客户端分发；不承载实现类，通过发布 POM 传递暴露 22 个运行时模块 |
| `apm-trace` | 手动 Span/Trace API，结束后通过 `Apm.emit` 上报 |
| `apm-otel-exporter` | 输出 OTel-compatible Span/Metric/Log Map；不负责 SDK/网络发送 |
| `apm-plugin` | AGP instrumentation + ASM slow-method 插桩 |
| `build-logic` | Android library convention plugin |
| `apm-sample-app` | 集成 15 个监控模块的演示应用；实际演示 IO/SQLite/WebView/IPC/线程池/Battery 显式接线，并显式配置 `logcat://sample` 输出 |
| `apm-benchmark` | 非发布双层性能门：AndroidX codec/SQLite 固定预算 + sample-App 启动/主线程/CPU/PSS/功耗/磁盘/热/24h/72h 离线重启 campaign |

## 五、统一事件模型

`ApmEvent` 的主要维度：

- identity：`eventId`, `module`, `name`, `kind`
- urgency：`severity`, `priority`
- occurrence：`timestamp`, `processName`, `threadName`
- context：`scene`, `foreground`, `globalContext`, `extras`
- payload：`fields`

`severity` 表示问题严重程度，`priority` 表示存储/上传顺序与资源竞争时的保留价值，两者不应混用。

持久化 `ApmEventCodec`：

- 当前写 format version 3，兼容读取 version 1/2
- codec 硬上限 2 MiB；SQLite 默认 durable 单事件软上限 256 KiB
- 单字符串最大 1 MiB
- 单 Map 最大 4096 项
- v3 对 null、String、Boolean、Byte/Short/Int/Long、Float/Double、Char、BigInteger、BigDecimal 写显式类型标签并恢复原类型
- BigInteger/BigDecimal 的 decimal text 最多 4096 字符，避免损坏行制造过量大数解析开销
- v1/v2 `fields` 保持历史字符串语义；v3 遇到其他任意对象时仍安全降级为有界字符串，未知 tag 拒绝该损坏事件

传输保留 Line Protocol 和零外部 runtime 依赖的 legacy standalone Protobuf；两者的 `fields` 继续按字符串输出，不因本地 codec v3 静默改变。显式 `PROTOBUF_ENVELOPE_V2` 使用 append-only event field 15 的 `ApmTypedValue`，batch 携带 schema/SDK/resource/稳定 batchId，并要求精确 whole-batch ACK；完整规范见 [Collector Wire Protocol V2](protocol/COLLECTOR_WIRE_V2.md)。`eventId` 在所有 wire、durable codec、SQLite 与多进程交接中保持稳定，服务端最终仍必须以该值实现幂等。

## 六、分发与宿主开销

`Apm.init` 先执行 runtime profile 校验，再创建 diagnostics/store/thread。默认 `COMPATIBILITY` 保留历史接入；`PRODUCTION_STRICT` 必须显式 `CollectionConsent.GRANTED`、SQLite durable outbox、PII 脱敏、关闭 debug logging，并使用精确 HTTPS endpoint 或显式非 Logcat custom uploader。`DENIED` 在所有 profile 下拒绝初始化。校验通过后 SDK 会复制宿主持有的配置集合；`Apm.emit` 在调用线程捕获 epoch 时间戳、线程名及顶层 fields/extras 快照，再把事件构建延迟到 dispatcher worker。直接构造 `ApmEvent` 的内部/测试接入同样会在 dispatcher 或多进程异步 hand-off 前冻结 fields/globalContext/extras，宿主后续修改不会改写已发生事件。业务上下文默认采用 `SYNCHRONOUS` 精确事件时刻快照，兼容既有语义，但 provider 必须 O(1)、无 IO、无等待锁。可能阻塞的 provider 应使用 `ASYNC_CACHED`：`apm-biz-context` 后台线程按 100 ms–24 h 有界周期刷新，emit 只原子读取最近一次成功的不可变快照；失败保留 LKG，首次成功前为空。登录、退出或租户切换可调用 `Apm.refreshBizContext()` 合并式触发后台刷新，重复调用不无界积压。两种模式都会复制宿主 map，recoverable provider 异常记录 internal error 而不向业务调用栈外泄。

`ApmClock` 明确区分两类时间：collector event timestamp、持久化 lease/age、文件 mtime 和 HTTP-date 使用 Unix epoch；延迟、超时、冷却、去重、限流、聚合、采样窗口使用单调时钟并对负 duration 归零。FPS 以相邻回调形成的实际 interval 数除以真实单调耗时，再按当前 refresh rate 封顶，同时上报 `windowDurationMs`；回调数不再被误当成完整帧区间数。

队列同时受 2048 条与默认 8 MiB 估算保留内存约束。调用线程基于已经冻结的标量/map 快照执行保守 O(n) 大小估算，不做 durable 序列化；producer 准入只做非等待 `tryLock` + `offer`。默认在总队列达到 75% 后，已占总容量 50% 的同一来源模块不能继续写入 NORMAL/LOW，给其他模块预留压力槽位。HIGH/CRITICAL 绕过该隔离门禁；满载时可在同一个 admission lock 内移除足够数量的最旧低优先级事件，使条数和字节两个维度都满足，同级保持先到先得。竞争、单事件超预算或无可用低优 victim 时立即丢弃并按准确 reason 计入自监控，业务线程不等待 worker 或其他 producer。模块占用和 `queuedBytes` 都随 offer/drain/淘汰在同一有界队列上维护，不序列化 payload。

worker 单轮 drain 最多 32 条：

1. 解析 lazy event
2. 签名动态采样；按全局→模块→事件覆盖，ERROR/FATAL 绕过
3. 可选聚合
4. 动态 rate limit；按全局→模块→事件覆盖，ERROR/FATAL 绕过
5. PII 脱敏（默认启用；仅 compatibility 可显式关闭，strict 禁止）
6. `appendBatch` 单事务落盘
7. 唤醒 persistent uploader

正常停止顺序：先切断新事件入口，再停止模块，排空 dispatcher，处理聚合残留，停止 uploader，关闭 store。排空均有超时上限。

同意撤回采用不同的隐私顺序：设置进程内 sticky gate 并切断新 emit，停止配置/模块/IPC producer，丢弃 dispatcher queue 且不 flush 聚合残留，停止 uploader 后清理 store。`Apm.revokeCollectionConsent(application)` 还会清理上一会话的 SQLite、File 兼容存储和 `.ipc/.tmp` artifacts，适合冷启动/已停止状态；无参版本未初始化且无法定位 app-private 目录时明确返回未清理。重新同意必须先调用 `grantCollectionConsent()` 再显式 `init`，不会自动复活。多进程宿主必须把撤回传播到每个 SDK 进程以关闭各自内存 producer。

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

可靠性优先级是宿主安全、telemetry durability、diagnostic completeness。dispatcher 当前仍以单 worker 顺序执行聚合、限流、脱敏和存储 hand-off；高水位模块隔离缓解入口 noisy-neighbor，8 MiB 估算预算限制 retained-memory 爆炸半径，两者都不等于多 worker 吞吐扩展。dispatcher 对单个 lazy factory/聚合/限流/脱敏的 recoverable `Exception` 单独降级，后续事件继续；fatal VM error 不转换成 drop。SQLite 的进程本地缓存计数在跨 store 删除后下限为 0，避免负缓存绕过容量淘汰。自定义同步 uploader 必须自行配置有界 IO，SDK 不尝试强杀任意宿主代码。

PII sanitization 默认开启。文本规则覆盖手机号、邮箱、身份证号和 URL 凭据；字段名保护会直接遮蔽 `authorization`、access/refresh token、password、API key、cookie、phone/email 等高置信字段，即使其值是数值或不符合文本正则。普通数值指标保持原类型；接入方仍需为自身业务字段补充自定义规则，不能把默认规则等同于完整合规证明。

`StorageType.FILE` 是 500 行 ring buffer 兼容路径，不提供成功确认、重启重放等 durable 语义，初始化会输出降级警告。

默认 HTTP uploader 支持静态协议身份 Header 和逐请求 `HttpHeaderProvider`。动态 provider 用于短期 Token，每次网络请求重新取值；provider 异常、Header 控制字符或覆盖 Content-Type/Content-Length/Host/`X-Apm-*` transport Header 时请求失败，durable 行保留。`enableDynamicHttpEndpoint=true` 后才读取签名键 `apm.upload.endpoint`，且远程值只接受无 user-info 的 HTTPS URL；非法值或 provider 异常回退 APK 内置 endpoint。compatibility 模式未配置或使用未知 scheme 时采用 payload-safe discard uploader：事件被确认丢弃以避免 outbox 无界增长，只输出一次不含 payload 的配置警告；开发期 Logcat 输出必须显式配置 `logcat://...`。strict built-in HTTP 在 factory 之前要求 HTTPS、`PROTOBUF_ENVELOPE_V2`、完整 fixed resource 和 batch headroom；V2 按实际 gzip 前 protobuf 字节拆分请求，2xx 只有 echo schema/batchId/eventCount 的整批 ACK 完全匹配才成功。显式非 Logcat custom uploader 自行承担等价协议。

`apm-remote-config` 通过认证 GET `/v1/config` 拉取配置，发送 app/environment/installation 身份与 ETag。响应按服务端 canonical JSON 规则重建签名字节，并用 APK 固定的 32 字节原始 Ed25519 公钥通过 Tink 验签。只有验签、revision 单调、同 revision 签名一致且 app-private 缓存同步提交成功后才发布；204 主动停用，304 更新可信时间锚点，网络失败沿用未过期 LKG。最高 revision 即使配置过期或停用也不会回退。Android 平台原生 Ed25519 保证从 API 33 才开始，因此 minSdk 24 使用官方支持 Android 24+ 的 Tink 实现。

## 八、多进程

多进程协调默认关闭。开启后：

- 主进程为 uploader process
- 子进程普通事件通过 lock-free queue 和原子 4 MiB pending 字节预算合批，producer 不等待 writer IO
- 唯一 500 ms fixed-delay writer 周期取出 pending；单轮最多 16,384 条，再按 100 行/文件字节拆分，不为每个事件创建任务
- raw codec 事件默认最多 256 KiB；单个 ready 文件最多 1 MiB
- 先写 `.tmp`，在跨进程文件锁内检查 16 MiB ready-directory 预算后发布为 `.ipc`
- critical 事件同步单文件发布，并把具体 file/directory budget reason 返回调用方
- 主进程每 5 秒扫描 ready 文件
- 文件 5 分钟过期

主进程先按文件大小拒绝超预算 ready 文件，再用流式逐行读取代替无界 `readLines()`；行数、Base64 行长度和解码后 payload 都有独立边界。该通道是本机文件 hand-off，不是跨设备传输；rename 失败时存在 copy fallback，仍应将其视为尽力保持完整性的本地协调机制。

## 九、SDK 自诊断

`SdkSelfMonitor` 每 60 秒生成 emit/drop/queue/upload latency/internal error 健康报告。每次真实 loss 同时累计总数、稳定 `SdkDropReason` 和事件优先级；`sdk_health` 同时输出 `queueSize` 与 `queueBytes`，并以 `dropReason.<reason>` / `dropPriority.<priority>` 数值字段展开。dispatcher byte budget、IPC pending/file/directory budget 都有独立 reason；兼容存储只返回总数时进入 `dropPriority.unattributed`。SQLite capacity eviction 和 outbox retry/age prune 返回并保留精确 priority counts。`dispatcherModuleIsolationDropCount` 继续单列高水位模块隔离造成的丢弃，并同时进入总 `dropCount`/drop rate。单 worker 还对 `resolve`、`sampling`、`aggregate`、`rateLimit`、`sanitize`、`storeHandoff` 六个固定阶段输出 `dispatcherStage.<stage>.count/avgMicros/p95UpperBoundMicros/maxMicros`；P95 是有界直方图桶的保守上界，store 按 batch、其他阶段按实际 invocation 计数，不能直接把各 count 当成同一分母。AutoThrottle 在 drop rate > 50% 或平均上传延迟 > 10 秒时立即保持关闭 LOW 模块，drop rate > 80% 时扩大到指定 NORMAL 模块；恢复必须连续 3 个周期同时满足 drop rate <= 20% 且平均上传延迟 <= 3 秒。阶段字段当前只提供证据，不参与自动降级。迟滞区间或再次退化会重置恢复计数，注册和动态配置也不能绕过当前自动降级集合；恢复时仍重新检查进程、签名动态配置和灰度门禁。

每份 `sdk_health` 先将仅含数值计数的摘要写入独立诊断 journal，再以 HIGH 优先级尝试普通事件管线。dispatcher 拥塞、采样或限流仍可能影响遥测副本，但不会抹掉独立本地摘要；诊断 sink 的 recoverable 失败也不能阻断事件尝试。

SDK 自诊断与普通 APM 事件是两个故障域：`ApmLogger` 继续输出 Logcat，同时把受控记录写入 200 条 / 4 MiB 内存环和按进程隔离的 app-private 滚动 JSONL；文件写入通过 256 条 / 4 MiB 非阻塞队列和 `apm-diagnostics-writer` 后台线程完成。每个进程默认保留 3 个 512 KiB 分片，磁盘预算约 1.5 MiB。

`ApmDiagnostics.status/snapshot/exportTo/clear` 及 `snapshotAsync/exportToAsync/clearAllProcesses` 支持现场状态、最近记录、聚合 ZIP 导出和明确范围的清理。每个 Android 进程拥有独立 journal 目录；内存环和写队列默认各有 4 MiB 字节预算，并保留原有条数预算。`status` 使用缓存资源计数，snapshot/导出读取文件时推荐异步 API。冷却期 writer 不提前出队，读/写故障独立计数；文件异常只更新本地状态并降级到内存 + 原始 Logcat，不重新进入 logger，避免递归。显式导出失败返回 `DiagnosticExportResult(success=false)`，自定义 store 的异常也不会逃逸。导出最多读取最近 16 个进程目录，合并结果受 10,000 条 / 16 MiB 双上限约束；目标不能覆盖活动 segment，manifest 带 SDK/process/session 与截断元数据。

结构化记录包含时间、级别、组件、错误码、进程、线程、异常类型、有限堆栈与栈指纹。消息最大 4 KiB，异常栈最大 16 KiB/64 帧，并脱敏常见 token/password/Authorization。事件 payload、业务上下文、请求正文、SQL 不进入诊断 journal。SDK 不自动上传诊断包。

## 十、配置默认值

| 配置 | 默认 | 说明 |
|---|---:|---|
| `runtimeProfile` | `COMPATIBILITY` | 保持源兼容；正式接入显式选择 `PRODUCTION_STRICT` |
| `initialCollectionConsent` | `UNSPECIFIED` | strict 必须显式 `GRANTED`；`DENIED` 总是拒绝 |
| `serializationFormat` | `LINE_PROTOCOL` | compatibility 保留 legacy；strict built-in HTTP 要求 `PROTOBUF_ENVELOPE_V2` |
| `resourceContext` | 空 | fixed service/version/environment/anonymous installation；strict V2 必填 |
| `maxUploadBatchBytes` | 1048576 | V2 gzip 前 envelope 预算；绝对上限 4 MiB，实际编码拆批 |
| `storageType` | `SQLITE` | durable outbox |
| `maxEventPayloadBytes` | 262144 | 单事件 durable payload 软上限，超限单独拒绝 |
| `maxStoredPayloadBytes` | 67108864 | 活跃 payload 逻辑预算，不含 SQLite page/WAL 开销 |
| `maxDispatcherQueueBytes` | 8388608 | dispatcher 估算保留内存预算；与 2048 条同时生效 |
| `maxIpcPendingBytes` | 4194304 | 子进程未发布普通事件的估算内存预算 |
| `maxIpcFileBytes` | 1048576 | 单个 ready IPC 文件的实际字节预算 |
| `maxIpcDirectoryBytes` | 16777216 | ready IPC 目录实际字节预算；锁内跨进程检查 |
| `enableDispatcherModuleIsolation` | true | 高水位隔离占用过多的 NORMAL/LOW 来源模块；HIGH/CRITICAL 绕过 |
| `dispatcherIsolationHighWatermarkPercent` | 75 | 启动隔离的队列水位百分比，运行时约束 1–100 |
| `dispatcherMaxModuleQueueSharePercent` | 50 | 压力期单模块占总容量上限，运行时不超过高水位 |
| `bizContextCaptureMode` | `SYNCHRONOUS` | 精确事件时刻快照；provider 必须 O(1)/无 IO/无等待锁，慢 provider 使用 `ASYNC_CACHED` |
| `bizContextRefreshIntervalMs` | 1000 ms | 异步缓存刷新周期，运行时约束到 100 ms–24 h |
| `endpoint` | 空 | 仅 compatibility 安全丢弃；strict 要求 HTTPS 或非 Logcat custom uploader |
| `rateLimitEventsPerWindow` | 10/60s | 按 module/name 分桶 |
| `enableAggregation` | false | 高频 metric 与 alert 去重不默认启用 |
| `enablePiiSanitization` | true | 文本规则 + 高置信敏感字段名；strict 禁止关闭 |
| `debugLogging` | false | 默认关闭 SDK 调试日志；strict 禁止开启 |
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

根构建统一 group/version、POM 元数据、sources JAR/AAR 和可选 signing。主构建、`apm-plugin`、`build-logic` 与独立 Maven consumer 均使用 Java 17 toolchain，同时允许 Gradle/AGP 支持的更新 JDK 作为 Gradle runtime；Android、纯 JVM、Gradle 插件与 consumer 的 Java/Kotlin 字节码目标统一为 17。`build-logic` 收敛发布型 Android library 的 compileSdk/minSdk/Java 版本；`apm-bundle` 不承载实现类，通过 `api(project(...))` 生成传递依赖 POM，为完整能力接入提供单一坐标；`apm-benchmark` 直接应用官方 Benchmark 插件并明确排除 Maven publication。`verifyReleasePerformanceBudgets` 把 connected microbenchmark 与 fail-closed verifier 串联；`run_device_soak.py` 生成显式物理机工件，`verifyDeviceSoakFromResults` 只验证显式 profile/result，不搜索旧文件。`apm-plugin` 作为 included build 独立测试。

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

2026-07-21 的客户端 P0 hardening 在 JDK 17.0.14 下通过 `:apm-core:testDebugUnitTest --no-daemon`：23 个 suite、153 个测试、0 failures/errors/skips；`python docs/verify_docs.py` 通过 40 个 Markdown 文件和 37 个本地链接。当天根 `testDebugUnitTest` 尝试因共享 Gradle distribution/transform cache 被外部清理而失效，不构成源码通过或失败基线；该旧状态已由下面 2026-07-22 的稳定缓存全根刷新取代。

2026-07-22 的第二批 hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest :apm-storage:testDebugUnitTest --no-daemon`：core 23 suites / 160 tests，storage 6 suites / 36 tests，均为 0 failures/errors/skips；storage 在分块 trim 加固后另以 `--rerun-tasks` 完整重跑 36 个测试并通过。`python docs/verify_docs.py` 再次通过 40 个 Markdown 文件和 37 个本地链接。这是 AutoThrottle 恢复与 durable payload 预算的定向增量证据；当前全根基线见下方闭环刷新。

2026-07-22 的第三批 measurement/self-observability hardening 在 JDK 17.0.14 下以 `--rerun-tasks` 强制重跑：FPS 4 suites / 31 tests，core 23 suites / 162 tests，均为 0 failures/errors/skips。补入 API 26 delayed-frame 门禁后又执行 `:apm-fps:testDebugUnitTest :apm-fps:lintDebug --no-daemon` 并通过；当前 `apm-fps` 与 `apm-core` lint 文本报告均为 `No issues found`，`python docs/verify_docs.py` 通过 40 个 Markdown 文件和 37 个本地链接。该定向证据覆盖 FPS 单调时间窗口、FrameMetrics primitive rolling accumulator、API 26 门禁和 `sdk_health` 独立摘要/HIGH 事件双通道；当前全根基线见下方闭环刷新。

2026-07-22 的第四批 dispatcher isolation hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon`：23 suites / 167 tests，0 failures/errors/skips，lint 为 `No issues found`；`python docs/verify_docs.py` 通过 40 个 Markdown 文件和 37 个本地链接。确定性压力测试覆盖默认模块隔离、其他模块容量保留、HIGH 绕过并淘汰旧低优事件、百分比约束、关闭开关、总 drop 与 `dispatcherModuleIsolationDropCount` 独立计数。该定向证据只证明共享入口的容量隔离；当前全根基线见下方，dispatcher 并行吞吐和真机性能仍是不同验证维度。

2026-07-22 的第五批 biz-context latency hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon`：24 suites / 172 tests，0 failures/errors/skips，lint 为 `No issues found`；`python docs/verify_docs.py` 通过 40 个 Markdown 文件和 37 个本地链接。测试覆盖同步不可变快照、异步 SDK 线程执行、首次空值/LKG、recoverable provider 失败、显式请求合并和公共 init/refresh/stop 生命周期。该定向基线证明 `ASYNC_CACHED` emit 不执行宿主 provider；`SYNCHRONOUS` 兼容模式仍依赖接入方遵守 O(1)/无 IO/无等待锁契约。

2026-07-22 的第六批 consumer distribution 验证在 JDK 17.0.14 下执行根 `publishToMavenLocal --no-daemon`、隔离 consumer `clean assembleDebug --no-daemon` 以及 `:apm-bundle:lintDebug :apm-bundle:assembleRelease --no-daemon`，全部通过。隔离 consumer 只声明 `com.apm:apm-bundle:0.1.0`，仍可编译来自 core、memory、network、model 和 OTel exporter 的代表性 API。Maven Local 当前包含 22 个 AAR、24 个 JAR 和 23 个 POM；bundle POM 传递暴露 22 个 `com.apm` 运行时制品，AAR 不承载 SDK 实现类。`python docs/verify_docs.py` 通过 41 个 Markdown 文件和 39 个本地链接。该结果证明本地发布与单依赖编译链，不代表 Maven Central 或其他外部仓库已经发布。

2026-07-22 的第七批 HttpURLConnection integration 验证在 JDK 17.0.14 下执行 `:apm-network:testDebugUnitTest :apm-network:lintDebug --rerun-tasks --no-daemon`：3 suites / 21 tests，0 failures/errors/skips，lint 为 `No issues found`。测试覆盖成功和 HTTP error 返回、headers/body IOException、宿主非网络异常、停止态、report sink recoverable 失败隔离及 fatal VM error 可见性。`python docs/verify_docs.py` 通过 41 个 Markdown 文件和 39 个本地链接。该结果证明显式 helper 的执行/异常契约，不替代真实代理、TLS、重定向、OEM 网络栈集成测试。

2026-07-22 的第八批 performance-budget 验证在 JDK 17.0.14 下执行 `python -m unittest discover -s apm-benchmark/tests -p "test_*.py"`：5 tests 通过；已有 emulator JSON 仅通过显式 `--allow-emulator` 验证 3 个预算匹配与归一化输出；`:apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --rerun-tasks --no-daemon` 构建通过。固定预算覆盖 durable encode 30 µs/48 allocations、decode 60 µs/72 allocations、32-event SQLite batch 8 ms/2,048 allocations；缺项、坏指标、超预算和默认 emulator 检测都会使 gate 失败。`python docs/verify_docs.py` 通过 42 个 Markdown 文件和 41 个本地链接。物理机仍因安装策略不能产生接受结果，因此该增量证明门禁代码与构建入口，不伪装成真机预算已通过。

2026-07-22 的第九批 typed-durable-field 验证在 JDK 17.0.14 下执行 `:apm-model:test :apm-storage:testDebugUnitTest :apm-benchmark:compileReleaseAndroidTestKotlin --rerun-tasks --no-daemon`：model 4 suites / 40 tests、storage 6 suites / 36 tests，均为 0 failures/errors/skips，benchmark AndroidTest Kotlin 编译通过。测试覆盖 12 类受支持标量的值与运行时类型保真、任意对象字符串 fallback、4,096 字符大数边界、v1/v2 读取以及未知 v3 tag fail-closed；storage 全套证明 SQLite append/read/claim/replay 未回归。`python docs/verify_docs.py` 通过 42 个 Markdown 文件和 41 个本地链接。该变更只升级本地 durable payload，不改变 Line/Protobuf 字符串 wire map。

2026-07-22 的客户端闭环全根刷新在 JDK 17.0.14 下执行根 `testDebugUnitTest --rerun-tasks --no-daemon`：92 suites / 605 tests；同一源码状态的 `:apm-model:test`：4 suites / 40 tests；included `apm-plugin test --rerun-tasks --no-daemon`：1 suite / 18 tests。全部为 0 failures/errors/skips。根 Android + model 形成当前 96 suites / 645 tests 的客户端基线，plugin 18 tests 独立报告；这取代 2026-07-16 的 595-test 旧根基线。`python docs/verify_docs.py` 通过 42 个 Markdown 文件和 41 个本地链接。

2026-07-22 的第十批 strict-production/consent hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest --rerun-tasks --no-daemon`：25 suites / 180 tests，0 failures/errors/skips；`:apm-core:lintDebug --rerun-tasks --no-daemon` 通过，文本报告为 `No issues found`；`python docs/verify_docs.py` 通过 42 个 Markdown 文件和 41 个本地链接。测试覆盖 compatibility/strict 配置、显式 consent、活动 runtime 清理、冷启动 dormant outbox 清理、sticky re-init 拒绝与 IPC artifact 删除。该结果是当前源码的 core 定向证据；前述 605-test 仍是最近全根运行，不将旧全根结果自动外推到本变更。

2026-07-22 的第十一批 collector-wire-V2 hardening 在 JDK 17.0.14 下执行 `:apm-model:test :apm-uploader:testDebugUnitTest :apm-uploader:lintDebug :apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon`：model 5 suites / 46 tests、uploader 4 suites / 24 tests、core 25 suites / 180 tests，均为 0 failures/errors/skips，两个 lint 报告均为 `No issues found`；`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 47 个本地链接。测试覆盖完整 scalar type mapping、append-only event field 15、稳定 batch identity、固定 resource、按实际编码字节拆分/拒绝、协议保留请求头、exact whole-batch ACK、strict 协议/resource 校验和 legacy 兼容。该结果是当前源码的定向证据；前述 605-test 仍是最近全根运行。

2026-07-22 的第十二批 critical-handoff/loss-attribution hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest :apm-core:lintDebug :apm-storage:testDebugUnitTest :apm-storage:lintDebug :apm-anr:testDebugUnitTest :apm-anr:lintDebug --rerun-tasks --no-daemon`：core 27 suites / 184 tests、storage 6 suites / 37 tests、ANR 5 suites / 26 tests，均为 0 failures/errors/skips，三个 lint 报告均为 `No issues found`；`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 47 个本地链接。测试覆盖 CRITICAL promotion、ANR 同步 hand-off 成功/失败、remote IPC rejection 归因、queue/storage/uploader drop reasons、reason/priority/reset 一致性、UNATTRIBUTED、SQLite capacity/prune priority recovery 与 fatal error 可见性。该结果是当前源码的定向证据；前述 605-test 仍是最近全根运行。

2026-07-22 的第十三批 time-semantics/event-snapshot hardening 在 JDK 17.0.14 下以 `--rerun-tasks` 执行 core 与 15 个监控/扩展模块的定向测试：77 suites / 527 tests，0 failures/errors/skips；根 `lintDebug --rerun-tasks --no-daemon` 通过。同一源码随后完整刷新：根 96 suites / 630 tests、model 5 suites / 46 tests、included plugin 1 suite / 18 tests，全部 0 failures/errors/skips；根 Android + model 当前为 101 suites / 676 tests，plugin 18 tests 独立报告，并取代前述 605-test 根基线。`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 47 个本地链接。覆盖 duration/expiry/dedup/rate-limit 的单调时钟、collector epoch 时间、span 逆序结束保护、按实际 interval 计算 FPS，以及直接事件在异步边界的 fields/globalContext/extras 不可变快照。

2026-07-22 的第十四批 cross-layer byte-budget hardening 在 JDK 17.0.14 下执行 `:apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon`：core 27 suites / 194 tests，0 failures/errors/skips，lint 为 `No issues found`；执行 `:apm-storage:testDebugUnitTest :apm-storage:lintDebug --rerun-tasks --no-daemon`：storage 6 suites / 37 tests，0 failures/errors/skips，lint 为 `No issues found`。覆盖 dispatcher 条数 + 8 MiB 估算字节双准入、多 victim 优先级淘汰、`queueBytes` 健康字段、IPC 4 MiB pending / 256 KiB event / 1 MiB file / 16 MiB directory 四层预算、lock-free pending + 单一 fixed-delay writer、同步 critical 精确拒绝原因和流式 ready-file 读取，并复核 SQLite 256 KiB/event + 64 MiB live payload 预算。同一源码完整刷新通过根 96 suites / 636 tests、model 5 suites / 46 tests、included plugin 1 suite / 18 tests，全部 0 failures/errors/skips；根 Android + model 当前为 101 suites / 682 tests，plugin 18 tests 独立报告，取代前述 630-test 根基线。`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 47 个本地链接。

2026-07-22 的第十五批 physical-device-soak gate 在 JDK 17.0.14 下执行 `python -m unittest discover -s apm-benchmark/tests -p "test_*.py"`：14 个 host tests 通过；`:apm-sample-app:assembleDebug :apm-sample-app:lintDebug :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon` 通过，sample lint 为 0 errors / 25 warnings。新增 A/B control、失败 uploader 离线积压、冷进程分段重启、主线程 primitive latency reservoir，以及 `/proc` CPU、PSS、app-private disk、UID batterystats、thermal 采集；`smoke`/`24h`/`72h` 校验对物理机、实际时长、重启次数、离线模式、资源上限和长稳功耗证据 fail closed。`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 48 个本地链接。该结果证明客户端/host harness 与构建链；Xiaomi 安装仍被设备策略拒绝，因此没有物理数值，也没有把 24/72 小时声明为已执行。

`apm-model`、`apm-core`、`apm-plugin` 与 sample 的代表性 class 文件均由 `javap -verbose` 确认为 major version 61，即 Java 17 字节码。同日另用 JDK 21.0.11 启动 Gradle，根构建配置与 `:apm-model:test` 成功，生成的 model class 仍为 major version 61。

2026-07-22 设备侧验证：ADB 可见 Xiaomi `22041216UC` 与 Android 17 emulator。当时新的 sample-App smoke runner 安装 5,916,050 字节 debug APK 时再次被 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 拒绝，尚未进入只清理 `com.apm.sample.debug` 数据的 acquisition 阶段；早先 benchmark APK 也被同一设备安全策略拒绝，因此当日未产生物理性能数值。emulator 在显式抑制 AndroidX 的 `EMULATOR` 环境门禁后完成三个 microbenchmark 方法并生成 JSON/Perfetto，但 runner 最终因 `IsolationActivity` 45 秒启动超时将任务标记失败。模拟器结果只证明 instrumentation 执行链，不作为真机性能结论。

2026-07-23 在同一台物理 Redmi/Xiaomi `22041216UC`（Android 13）上重新验证。JDK 17.0.14 下 `:apm-sample-app:assembleDebug :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon` 通过；启用设备的 USB 安装控制后，sample 与 benchmark AndroidTest APK 均可通过直接 ADB 安装。MIUI 会拒绝 benchmark 进程后台拉起 AndroidX `IsolationActivity`，因此只对 `com.apm.benchmark.test` 临时允许该 app-op，正式 `AndroidBenchmarkRunner` 随后无错误完成 3/3 测试，结束后权限已恢复为原始 `ignore`。fail-closed JSON 预算验证通过：durable encode median `4,640.93 ns` / `22.00 allocations`，decode `4,841.81 ns` / `46.00 allocations`，32-event SQLite transaction `1,258,990.52 ns` / `1,400.21 allocations`。但 Gradle aggregate task 的 session-based APK 安装仍被该 OEM 以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，因此不能把 Gradle 一键任务写成通过；接受数值来自直接安装同一构建 APK 后运行其声明的正式 runner，且未抑制 AndroidX 环境错误。

同日最初完整执行两次物理 `smoke` acquisition。两次都满足实际时长、2 次进程启动、离线模式、启动增量、SDK init、主线程操作 P95、PSS、app-private disk 与 thermal 约束，但平均 CPU 分别为 `28.425%` 和 `32.046%`，连续超过 checked-in `20%` 上限，校验器均 fail closed。当时的阶段性结论是“microbenchmark 三项预算通过、smoke CPU 预算失败”；该失败随后按下段证据完成归因、修复与原预算复验。

随后保持 `device-soak-budgets.json` 的 `20%` smoke CPU 上限不变执行归因。稳定区间的线程级 `/proc` 采样显示 SDK-enabled 主线程约 `26.4%`，而 SDK-disabled control 主线程约 `0.8%`；10 events/second 下 `Apm.emit` P95 约 `1.7ms`，不足以解释差值。根因是 FPS monitor 在静态页面仍持续 repost Choreographer callback，主动把主线程按每个 VSync 唤醒。修复后 API 24+ 以仅在真实渲染时触发的 FrameMetrics 为主源，FrameMetrics 禁用或注册失败时才保留 Choreographer fallback。JDK 17.0.14 下 FPS `4` suites / `34` tests、lint 与 sample debug APK 构建通过，测试为 0 failures/errors/skips，lint 为 `No issues found`。同一 Redmi、同一配置、相同 APK SHA-256 `e22185f6b09182e5705cea27d80f74f3ac4f05d89ac2223638c02bc4e8f55c1d` 的两轮完整 smoke 分别以 CPU `12.928%`、`12.362%` 通过原 `20%` 门禁；实际时长 `30.562s` / `30.534s`，每轮 2 次进程启动，主线程 P95 `1,853.462us` / `1,796.539us`，其余 smoke 预算也全部通过。该证据关闭 smoke CPU 缺口，但不替代尚未执行的 `24h`、`72h` 与长稳功耗验收。

同日继续修正 device-soak 的 A/B 指标口径。历史 result schema v1 的 `cpuAveragePercent` 实际是 enabled segments 的绝对进程 CPU，只有 `startupDeltaMs` 使用 control 差值；当前 result schema v2 保留这个绝对门禁字段，并新增 `cpuControlPercent`、`cpuEnabledAveragePercent` 和带符号的 `cpuDeltaPercent`。校验器会从 control/enabled 原始 jiffies 与 elapsed samples 独立重算并核对三者，旧 schema v1 工件仍按原绝对 CPU 语义兼容。`device-soak-budgets.json` 未修改，17 个 host Python tests 通过，已接受的 schema-v1 smoke 工件继续通过原 `20%` 上限。此次只是 host 证据模型增强，没有产生新的 schema-v2 真机结果，也不能把单一前置 control 的 delta 当成 24h/72h 配对因果估计。

同日完成 `apm-uploader` 模块内线程策略收口。由于底层 uploader 不能反向依赖 `apm-core`，`RetryingApmUploader` 继续保留自己的有序 worker 和 delayed-retry scheduler；两者现在统一由模块内命名工厂创建，显式设置 daemon 与 `Thread.MIN_PRIORITY`，并保持 `apm-upload-retry` / `apm-upload-retry-scheduler` 稳定线程名。JDK 17.0.14 下 `:apm-uploader:testDebugUnitTest :apm-uploader:lintDebug --rerun-tasks --no-daemon` 通过 4 suites / 25 tests，0 failures/errors/skips，lint 为 `No issues found`；测试在真实 delegate 调用线程上核对名称、daemon 和 priority，而不是只检查构造参数。`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 49 个本地链接，两个 DOCX 派生报告也已从同一源重新生成并校验容器及正文。

同日完成 dispatcher 分阶段尾延迟证据。仅在 self-monitor 开启时，worker 以单调纳秒包围六个固定阶段，使用 6 × 22 个固定桶、无逐样本对象分配的短同步直方图，并在周期报告时生成 count、向上取整平均微秒、P95 桶上界和最大微秒；关闭 self-monitor 时直接执行原 block，不读取时钟。JDK 17.0.14 下 `:apm-core:testDebugUnitTest :apm-core:lintDebug --rerun-tasks --no-daemon` 通过 27 suites / 197 tests，0 failures/errors/skips，lint 为 `No issues found`。测试覆盖确定性平均/P95 上界/max/reset、健康事件与独立 journal 字段一致，以及真实 dispatcher 配置全阶段均被观测。`python docs/verify_docs.py` 通过 43 个 Markdown 文件和 49 个本地链接，两个 DOCX 报告重新生成并确认包含 P95 证据。该结果证明证据机制，不代表已有真机/24h 尾延迟分布，也不触发自动并行化。

仓库没有外部 Maven 发布凭据或已完成的 Maven Central 发布；`publishToMavenLocal` 成功不代表外部仓库已发布。

## 十二、测试策略

102 个测试/benchmark 文件覆盖 strict profile/consent/活动与冷启动撤回、V2 typed/resource/batch identity/byte split/exact ACK、critical priority promotion/ANR 同步 hand-off/IPC failure 分类、配置默认值、事件 identity/typed codec v1-v3/legacy Protobuf、dispatcher 单事件故障隔离/fatal 边界/条数与字节准入/多 victim 优先级淘汰/单模块高水位隔离与关闭开关/固定阶段延迟直方图、IPC pending/event/file/directory 字节预算与单一周期 writer、drop reason/priority/UNATTRIBUTED 归因、业务上下文和直接事件异步快照、单调 duration/expiry/dedup/rate-limit 与 epoch collector 时间、签名配置 canonical JSON/Ed25519/HTTP/ETag/LKG/过期/rollback/equivocation、动态 kill switch/采样/限流/endpoint/短期 Header、PII、聚合/指纹、durable outbox migration/lease/concurrency/固定种子状态机、uploader worker/scheduler 线程命名、daemon 与后台优先级、GC 分配/回收窗口、IO 吞吐窗口、SQLite QueryPlan gate/现代 SCAN 解析、IPC 文件、SDK 诊断脱敏/JSONL/滚动/导出失败数据化/并发降级、Provider 自动初始化/no-op/错误隔离、Memory Reporter/OOM/Hprof 截断输入/ViewModel 引用/真实采样、Network 请求分类/聚合/phase 截断/HttpURLConnection 异常语义、JNI 静态绑定契约、ASM 正常/异常出口、Binder/线程池/WebView、FPS 实际 interval 定义与 FrameMetrics primitive rolling accumulator 核心计算、两个 AndroidX Microbenchmark 类，以及 microbenchmark/device-soak host gate 的通过、解析、聚合、时长、重启、功耗、超限和 emulator 完整性分支。

测试通过不能代替以下验证：

- 真机 Native 行为与符号化
- 真机真正执行进程被杀/断电/磁盘满场景
- 物理设备 `24h` / `72h` 与长稳功耗、热和磁盘验收
- 真机跑满 24/72 小时的功耗、内存、离线和重启结果
- 多 OEM/Android 版本兼容
- 真实 Collector 协议与服务端幂等

## 十三、客户端完成边界与外部工作

仓库内可完成的客户端缺口已收口：单依赖 `apm-bundle` 分发、strict production profile/显式 consent/撤回清理、版本化 protobuf V2 typed/resource/batch/size/ACK 契约、Crash/ANR 同步 critical hand-off、按 drop reason/priority 的损失证据、稳定事件身份、SQLite v3 additive migration、typed durable codec v3 与 v1/v2 兼容读取、本地去重、claim/lease/expiry、owner-aware ACK、dispatcher/IPC/SQLite 跨层条数与字节预算、逐请求短期鉴权、签名配置/LKG/kill switch/采样/限流/endpoint、优先级感知入口背压与单模块高水位隔离、业务上下文同步契约与异步 LKG 缓存、带迟滞恢复的 AutoThrottle、默认隐私保护、运行时配置/payload 快照、直接事件异步 map 冻结、epoch/单调时钟职责分离、显式 OkHttp/HttpURLConnection/Binder/WebView/线程池公共 API、按实际 interval 定义的 FPS、FrameMetrics 无逐帧对象分配滚动累计、`sdk_health` 双通道、自诊断、固定 microbenchmark 预算，以及 fail-closed 的真机 A/B/离线/重启 smoke/24h/72h gate 均已实现。手动与 Provider 自动初始化现在有明确互斥文档和生命周期测试；sample 对 IO、SQLite、WebView、IPC、线程池与 Battery 使用真实显式 API，而不只注册模块。

本地 durable round-trip 通过 codec v3 恢复受支持标量类型，旧 v1/v2 行仍读取为字符串。legacy Line Protocol/standalone Protobuf 继续输出字符串 field map；新 `PROTOBUF_ENVELOPE_V2` 已以独立 schema 提供 typed wire fields，不能把它与 durable codec tag 或旧 endpoint 混用。生产落地仍需 Collector 按冻结协议部署、返回 exact ACK 并按 eventId 去重。

生产 Collector、鉴权/租户、服务端幂等、查询聚合/告警/Dashboard、Native 后台符号化、外部 Maven 发布、云端 runner 接线，以及在原预算 smoke 已通过后继续跑满 24h/72h、功耗仪或 UID、热与磁盘数值，全部依赖外部系统或设备。唯一任务清单和验收条件由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 维护。

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
- `docs/architecture/21_apm-bundle.md`：单依赖分发的依赖集合、边界与取舍
- `docs/AndroidAPM_第一性原理SDK评审_2026-07-21.md`：其他 AI 初始评审的源码闭环复核；按证据等级区分客户端架构、短时真机、长稳/OEM 和端到端产品准入，不作为生产验收证书
- `docs/APM_Review_2026-07-08.md`：历史评审与当前处置状态
- `docs/APM_Optimization_2026-07-08.md`：历史优化建议与落地状态
- `docs/architecture/generated-diagrams/`：由当前架构同步生成的 SVG/PNG
- `docs/APM_对比报告.docx`、`docs/APM_框架对比报告.docx`：可分发报告产物
- 历史原始附件已移除；当前事实来源为源码和维护中的 Markdown 文档
