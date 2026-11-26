# Android APM 项目文档

> 文档同步：2026-11-26｜27 个构建单元｜169 个主源码文件（164 Kotlin + 4 C + 1 proto）｜131 个测试/benchmark 文件

## 一、项目结论

AndroidAPM 是模块化 Android 端 APM SDK。它覆盖采集、统一事件、开销保护、持久化、跨进程交接、动态短期鉴权、签名远程配置和上传；生产 Collector、查询聚合、告警、Dashboard、Native 符号化及托管服务不在仓库内。

当前最成熟的部分不是单个监控算法，而是所有模块共享的可靠事件管线：

```text
monitor module
  -> Apm.emit
  -> priority-aware bounded queue (2048 events / 8 MiB estimated retained bytes; default 75% high-water / 50% per-module NORMAL/LOW share)
  -> signed dynamic sampling / PII before optional aggregation / dynamic rate limit / PII for direct events
  -> appendBatch (up to 32)
  -> SQLite durable outbox v4 (50,000 rows / 64 MiB live payload, 256 KiB per event, unique eventId; v4 additive claim-order index)
  -> claim(owner, lease, expiry) -> PersistentUploadWorker
  -> batch/custom uploader
  -> integrator-owned collector
```

Crash/ANR 关键事件通过 `Apm.emitCriticalSync` 绕过共享队列、采样、聚合与限流，同步到 SQLite 或 critical IPC hand-off point；较低调用方 priority 自动提升为 CRITICAL，返回成功前不执行网络 IO。上传进程消费 CRITICAL IPC 时仍同步落 store，只有下游接受后删除 ready 文件；false、recoverable 存储失败或 consumer 未就绪会保留整文件重试，已发布 `.ipc` 不按年龄先行删除，只有未完成 `.tmp` 在 5 分钟后清理。每个事件拥有稳定 `eventId`，上传 Worker 原子 claim 后由 owner ACK/失败释放，租约过期可重领。上传成功后删除，失败保留并重试；这是 at-least-once，不是 exactly-once，网络响应不确定或整文件重试时服务端仍须按 `eventId` 去重。

## 近期变更

按日期的功能和构建变更见 [变更历史](CHANGELOG.md)。依赖排查、历史测试和真机证据见 [历史验证记录](HISTORICAL_VERIFICATION.md)。

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
8. 当前 strict Collector 接入读 `docs/protocol/COLLECTOR_WIRE_V3.md`
9. V2 兼容接入再读 `docs/protocol/COLLECTOR_WIRE_V2.md`

`docs/` 属于项目交付并纳入 Git；`.workbuddy/`、`.github/`、`.claude/` 是本地状态，保持忽略。

## 三、工程基线

| 项目 | 当前值 |
|---|---|
| 分支 | `develop` |
| runtime tip | 使用 `git log --oneline -n 10` 查看；远程配置实现与本文档同一交付提交 |
| root Gradle subproject | 25 |
| included build | 2：`apm-plugin`、`build-logic` |
| 总构建单元 | 27 |
| 主源码 | 169：164 Kotlin + 4 C + 1 proto |
| 测试/benchmark 源文件 | 131（Gradle 的 `src/test` 与 `src/androidTest`，另有 5 个 Python 测试文件） |
| Kotlin | 2.2.21 |
| AGP | 9.5.2 |
| Gradle | 9.6.0 |
| Gradle daemon | JDK 21 |
| Java toolchain | 21 |
| JVM bytecode | Java 21 |
| Android SDK | compileSdk 36 / minSdk 24 / targetSdk 34 |

不要在文档中维护“最新文档提交号”；文档提交本身会让该值立即过期。使用 `git log --oneline -n 10` 查看实际 tip。

## 四、模块拓扑

### 4.1 基础模块

| 模块 | 核心职责 | 关键类 |
|---|---|---|
| `apm-model` | 统一事件、优先级、legacy wire、V2/V3 typed batch envelope、occurrence 与持久化 codec | `ApmEvent`, `ApmOccurrenceContext`, `ApmEventCodec`, `ProtobufSerializer`, `ApmBatchEnvelopeSerializer` |
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
| `apm-sample-app` | 演示 15 个监控模块、`apm-trace`/`apm-otel-exporter` 与宿主显式接线。默认输出 `logcat://sample`，可配置 Strict V3 发送到测试 Collector。 |
| `apm-benchmark` | 非发布双层性能门：AndroidX codec/SQLite 固定预算 + sample-App 启动/主线程/CPU/PSS/功耗/磁盘/热/24h/72h 离线重启 campaign |

## 五、统一事件模型

`ApmEvent` 的主要维度：

- identity：`eventId`, `module`, `name`, `kind`
- urgency：`severity`, `priority`
- occurrence：`timestamp`, `processName`, `threadName`，以及 V3 的 `serviceVersion/versionCode/appBuild/variant/installation/nativeFrames` snapshot
- context：`scene`, `foreground`, `globalContext`, `extras`
- payload：`fields`

`severity` 表示问题严重程度，`priority` 表示存储/上传顺序与资源竞争时的保留价值，两者不应混用。

持久化 `ApmEventCodec`：

- 当前写 format version 4，兼容读取 version 1/2/3
- codec 硬上限 2 MiB；SQLite 默认 durable 单事件软上限 256 KiB
- 单字符串最大 1 MiB
- 单 Map 最大 4096 项
- v3 对 null、String、Boolean、Byte/Short/Int/Long、Float/Double、Char、BigInteger、BigDecimal 写显式类型标签并恢复原类型；v4 在其后 append occurrence snapshot
- BigInteger/BigDecimal 的 decimal text 最多 4096 字符，避免损坏行制造过量大数解析开销
- v1/v2 `fields` 保持历史字符串语义，v3 行继续读取为 typed fields 且 occurrence 为缺失；v4 遇到其他任意对象时仍安全降级为有界字符串，未知 tag 拒绝该损坏事件
- 非法/overlong UTF-8、surrogate、越界 code point、截断和尾随字节全部 fail closed
- string/map count 必须能由剩余字节承载后才分配，encode 在 2 MiB 总预算处停止扩容

传输保留 Line Protocol 和零外部 runtime 依赖的 legacy standalone Protobuf；两者的 `fields` 继续按字符串输出，不因本地 codec 升级静默改变。`PROTOBUF_ENVELOPE_V2` 保留 append-only field 15 typed scalar、batch resource 和 exact ACK 兼容语义；strict built-in HTTP 使用 `PROTOBUF_ENVELOPE_V3`，要求每个 event 额外携带 field 16 occurrence，并以 `b3-` batch ID 精确确认。完整规范见 [V3](protocol/COLLECTOR_WIRE_V3.md) 和 [V2](protocol/COLLECTOR_WIRE_V2.md)。`eventId` 在所有 wire、durable codec、SQLite 与多进程交接中保持稳定，服务端最终仍必须以该值实现幂等与冲突检测。

## 六、分发与宿主开销

`Apm.init` 先执行 runtime profile 校验，再创建 diagnostics/store/thread。默认 `COMPATIBILITY` 保留历史接入；`PRODUCTION_STRICT` 必须显式 `CollectionConsent.GRANTED`、SQLite durable outbox、PII 脱敏、关闭 debug logging，并使用精确 HTTPS endpoint 或显式非 Logcat custom uploader。strict built-in HTTP 进一步要求 `PROTOBUF_ENVELOPE_V3` 和三参数 `Apm.init(application, config, occurrenceContext)`；当前自动 Provider 只能调用二参数 overload，因此不能承担 V3 初始化。`DENIED` 在所有 profile 下拒绝初始化。校验通过后 SDK 会复制宿主持有的配置集合与 occurrence/native-frame list；`Apm.emit` 在调用线程捕获 epoch 时间戳、线程名及顶层 fields/extras 快照，再把事件构建延迟到 dispatcher worker。所有经 `ApmContext` 进入 V3 的事件在异步/IPC/SQLite hand-off 前绑定 init-time release/build/installation，模块只能补充事件自己的 Native frames，不能覆盖 host 身份。直接构造 `ApmEvent` 的内部/测试接入同样会在 dispatcher 或多进程异步 hand-off 前冻结 fields/globalContext/extras/occurrence，宿主后续修改不会改写已发生事件。业务上下文默认采用 `SYNCHRONOUS` 精确事件时刻快照，兼容既有语义，但 provider 必须 O(1)、无 IO、无等待锁。可能阻塞的 provider 应使用 `ASYNC_CACHED`：`apm-biz-context` 后台线程按 100 ms–24 h 有界周期刷新，emit 只原子读取最近一次成功的不可变快照；失败保留 LKG，首次成功前为空。登录、退出或租户切换可调用 `Apm.refreshBizContext()` 合并式触发后台刷新，重复调用不无界积压。两种模式都会复制宿主 map，recoverable provider 异常记录 internal error 而不向业务调用栈外泄。

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

热路径分配已按行为保持原则收敛：worker 复用每批落盘缓冲，聚合吞没路径不再分配输出 list，动态策略键与敏感字段名判定各有条目数和单标识符长度边界；公开脱敏 API 对 clean map 仍返回隔离快照，只有 dispatcher 入队时已经冻结所有权的内部路径才零命中共享引用，首个变化项的自定义规则只执行一次。内置脱敏规则带可证明的最短命中长度快拒，bizContext 为空时直接共享冻结 defaultContext；V2 上传拆批每事件只编码一次（`ApmBatchEnvelopeSerializer.serializeWithinBudget` 增量公共 API，与逐候选全量序列化的拆分点/batchId/payload 逐字节一致并有等价性测试），SQLite `pendingCount` 用索引覆盖 `COUNT(*)` 与 `sqlite_sequence` 水位替代常规全表 BLOB 扫描，prune 用单次 GROUP BY 替代三趟同条件扫描，claim 的 payload 解码移到租约事务提交之后。所有改动均有等价性/回归测试，wire、durable codec 与 at-least-once 语义不变。

正常停止顺序：先切断新事件入口，再停止模块，排空 dispatcher，处理聚合残留，停止 uploader，关闭 store。排空均有超时上限。

同意撤回采用不同的隐私顺序：设置进程内 sticky gate 并切断新 emit，停止配置/模块/IPC producer，丢弃 dispatcher queue 且不 flush 聚合残留，停止 uploader 后清理 store。`Apm.revokeCollectionConsent(application)` 还会清理上一会话的 SQLite、File 兼容存储和 `.ipc/.tmp` artifacts，适合冷启动/已停止状态；无参版本未初始化且无法定位 app-private 目录时明确返回未清理。重新同意必须先调用 `grantCollectionConsent()` 再显式 `init`，不会自动复活。多进程宿主必须把撤回传播到每个 SDK 进程以关闭各自内存 producer。

## 七、持久化与上传

默认 `SQLiteEventStore`：

- schema v4，v2/v3 均通过 additive migration 保留全部 pending 行（v2 补 `legacy-<rowId>`，v3 只加 claim 排序索引 `idx_priority_desc_ts`，支撑 `priority DESC, timestamp ASC` 的 claim/read 排序）；WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启
- 默认容量同时受 50,000 行与 64 MiB 活跃 payload 逻辑预算约束；逻辑预算不等于 SQLite page/WAL 物理文件大小
- 默认单事件 durable payload 软上限 256 KiB；编码/超限失败只拒绝该事件，同批有效事件仍在一个事务中落盘
- 批量事务写入
- 缓存行数、payload 字节和数据库持久化 AUTOINCREMENT 水位；每批 append 在写事务内检查插入水位，每 64 条 insert 与每次 `pendingCount()` 比对索引覆盖的 `COUNT(*)` 和 `sqlite_sequence`。行数检测普通增删，插入水位检测净行数不变的 delete + insert；漂移时用单条 SQL 快照回读三个真值，常规路径零 BLOB 扫描
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

PII sanitization 默认开启。文本规则覆盖手机号、邮箱、身份证号和 URL 凭据；字段名保护会直接遮蔽 `authorization`、auth/authentication/auth-header、access/refresh token、password、API key、cookie、phone/email 等高置信字段，即使其值是数值或不符合文本正则。字段名大小写/分隔符变体统一处理，`author` 不误伤；内置 Regex 预编译。普通数值指标保持原类型；接入方仍需为自身业务字段补充自定义规则，不能把默认规则等同于完整合规证明。

`StorageType.FILE` 是 500 行 ring buffer 兼容路径，不提供成功确认、重启重放等 durable 语义，初始化会输出降级警告。

默认 HTTP uploader 支持静态协议身份 Header 和逐请求 `HttpHeaderProvider`。动态 provider 用于短期 Token，每次网络请求重新取值；provider 异常、Header 控制字符或覆盖 Content-Type/Content-Length/Host/`X-Apm-*` transport Header 时请求失败，durable 行保留。`enableDynamicHttpEndpoint=true` 后才读取签名键 `apm.upload.endpoint`，且远程值只接受无 user-info 的 HTTPS URL；非法值或 provider 异常回退 APK 内置 endpoint。compatibility 模式未配置或使用未知 scheme 时采用 payload-safe discard uploader：事件被确认丢弃以避免 outbox 无界增长，只输出一次不含 payload 的配置警告；开发期 Logcat 输出必须显式配置 `logcat://...`。strict built-in HTTP 在 factory 之前要求 HTTPS、`PROTOBUF_ENVELOPE_V3`、完整 fixed resource、完整 occurrence 和 batch headroom；V2/V3 都按实际 gzip 前 protobuf 字节拆分请求，2xx 只有 echo schema/batchId/eventCount 的整批 ACK 完全匹配才成功。V2 的 release/resource 只标为 `BATCH_DECLARED`，V3 occurrence 才是 `OCCURRENCE_BOUND`。schema/eventCount 只接受无前导零/正号的规范正十进制，batchId 精确匹配；`Retry-After` 超大秒数做饱和乘法后再受 60 秒 worker 上限约束。显式非 Logcat custom uploader 自行承担等价协议。

`apm-remote-config` 通过认证 GET `/v1/config` 拉取配置，发送 app/environment/installation 身份与 ETag。响应和 app-private LKG 共用 strict parser：绝对限制 2 MiB UTF-8、32 层、65,536 nodes、1,024-byte key/1 MiB string，拒绝重复 key 后才按服务端 canonical JSON 规则重建签名字节。APK 最多固定 16 把规范 Base64 的 32 字节原始 Ed25519 公钥；keyId 限 128 UTF-8 bytes，detached signature 必须无空白并严格解码为 64 bytes，再由 Tink 验签。只有验签、revision 单调、同 revision 签名一致且 app-private 缓存同步提交成功后才发布；204 主动停用，304 更新可信时间锚点，网络失败沿用未过期 LKG。最高 revision 即使配置过期或停用也不会回退。Android 平台原生 Ed25519 保证从 API 33 才开始，因此 minSdk 24 使用官方支持 Android 24+ 的 Tink 实现。

## 八、多进程

多进程协调默认关闭。开启后：

- 主进程为 uploader process
- 子进程普通事件通过 lock-free queue 和原子 4 MiB pending 字节预算合批，producer 不等待 writer IO
- 唯一 500 ms fixed-delay writer 周期取出 pending；单轮最多 16,384 条，再按 100 行/文件字节拆分，不为每个事件创建任务
- raw codec 事件默认最多 256 KiB；单个 ready 文件最多 1 MiB
- 先写 `.tmp`，在跨进程文件锁内检查 16 MiB ready-directory 预算后发布为 `.ipc`
- critical 事件同步单文件发布，并把具体 file/directory budget reason 返回调用方
- 主进程每 5 秒扫描 ready 文件
- ready `.ipc` 仅在下游接受后删除，不按年龄先行删除；16 MiB directory budget 限制空间
- 未完成 `.tmp` 5 分钟后清理

主进程先按文件大小拒绝超预算 ready 文件，再用流式逐行读取代替无界 `readLines()`；行数、Base64 行长度和解码后 payload 都有独立边界。该通道是本机文件 hand-off，不是跨设备传输；rename 失败时存在 copy fallback，仍应将其视为尽力保持完整性的本地协调机制。

## 九、SDK 自诊断

`SdkSelfMonitor` 每 60 秒生成 emit/drop/queue/upload latency/internal error 健康报告。每次真实 loss 同时累计总数、稳定 `SdkDropReason` 和事件优先级；`sdk_health` 同时输出 `queueSize` 与 `queueBytes`，并以 `dropReason.<reason>` / `dropPriority.<priority>` 数值字段展开。dispatcher byte budget、IPC pending/file/directory budget 都有独立 reason；兼容存储只返回总数时进入 `dropPriority.unattributed`。SQLite capacity eviction 和 outbox retry/age prune 返回并保留精确 priority counts。`dispatcherModuleIsolationDropCount` 继续单列高水位模块隔离造成的丢弃，并同时进入总 `dropCount`/drop rate。单 worker 还对 `resolve`、`sampling`、`aggregate`、`rateLimit`、`sanitize`、`storeHandoff` 六个固定阶段输出 `dispatcherStage.<stage>.count/avgMicros/p95UpperBoundMicros/maxMicros`；P95 是有界直方图桶的保守上界，store 按 batch、其他阶段按实际 invocation 计数，不能直接把各 count 当成同一分母。AutoThrottle 在 drop rate > 50% 或平均上传延迟 > 10 秒时立即保持关闭 LOW 模块，drop rate > 80% 时扩大到指定 NORMAL 模块；恢复必须连续 3 个周期同时满足 drop rate <= 20% 且平均上传延迟 <= 3 秒。阶段字段当前只提供证据，不参与自动降级。迟滞区间或再次退化会重置恢复计数，注册和动态配置也不能绕过当前自动降级集合；恢复时仍重新检查进程、签名动态配置和灰度门禁。

每份 `sdk_health` 先将仅含数值计数的摘要写入独立诊断 journal，再以 HIGH 优先级尝试普通事件管线。dispatcher 拥塞、采样或限流仍可能影响遥测副本，但不会抹掉独立本地摘要；诊断 sink 的 recoverable 失败也不能阻断事件尝试。

SDK 自诊断与普通 APM 事件是两个故障域：`ApmLogger` 继续输出 Logcat，同时把受控记录写入 200 条 / 4 MiB 内存环和按进程隔离的 app-private 滚动 JSONL；文件写入通过 256 条 / 4 MiB 非阻塞队列和 `apm-diagnostics-writer` 后台线程完成。每个进程默认保留 3 个 512 KiB 分片，磁盘预算约 1.5 MiB。

`ApmDiagnostics.status/snapshot/exportTo/clear` 及 `snapshotAsync/exportToAsync/clearAllProcesses` 支持现场状态、最近记录、聚合 ZIP 导出和明确范围的清理。每个 Android 进程拥有独立 journal 目录；内存环和写队列默认各有 4 MiB 字节预算，并保留原有条数预算。`status` 使用缓存资源计数，snapshot/导出读取文件时推荐异步 API。冷却期 writer 不提前出队，读/写故障独立计数；文件异常只更新本地状态并降级到内存 + 原始 Logcat，不重新进入 logger，避免递归。显式导出失败返回 `DiagnosticExportResult(success=false)`，自定义 store 的异常也不会逃逸。导出最多读取最近 16 个进程目录，合并结果受 10,000 条 / 16 MiB 双上限约束；目标不能覆盖活动 segment，manifest 带 SDK/process/session 与截断元数据。

`ApmDiagnostics.hostIntegrationSnapshot()` 另行提供不访问文件的显式宿主接线证据，覆盖 Network、SQLite、IPC、WebView、线程池、Battery 和 IO。每项分别报告模块是否运行、当前显式安装/注册数、当前 init/stop 会话内观察到的信号数和最后观察时间；`NO_RUNTIME_EVIDENCE` 只表示对应宿主操作尚未在本会话发生，不能被当成静态接入失败。WebView/线程池/可用 Native IO 可报告当前 registration，其余入口在实际 callback/helper/wrapper 执行后报告 `OBSERVED`。停止模块清零活跃 registration，重新 init 清除旧会话证据。注册表只保留固定枚举、计数和时间戳，不保留 URL、SQL、路径、WebView、executor 或其他宿主数据，也不依赖 diagnostics journal 开关。

结构化记录包含时间、级别、组件、错误码、进程、线程、异常类型、有限堆栈与栈指纹。消息最大 4 KiB，异常栈最大 16 KiB/64 帧，并脱敏常见 token/password/Authorization。事件 payload、业务上下文、请求正文、SQL 不进入诊断 journal。SDK 不自动上传诊断包。

## 十、配置默认值

| 配置 | 默认 | 说明 |
|---|---:|---|
| `runtimeProfile` | `COMPATIBILITY` | 保持源兼容；正式接入显式选择 `PRODUCTION_STRICT` |
| `initialCollectionConsent` | `UNSPECIFIED` | strict 必须显式 `GRANTED`；`DENIED` 总是拒绝 |
| `serializationFormat` | `LINE_PROTOCOL` | compatibility 保留 legacy；strict built-in HTTP 要求 `PROTOBUF_ENVELOPE_V3` |
| V3 occurrence | 无 | strict built-in HTTP 由三参数 `Apm.init` 提供完整 release/build/variant/installation snapshot；自动 Provider 不支持 |
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

此前保留为弃用兼容占位的 17 项声明（含 `IpcConfig.enableBinderHook`、`WebviewConfig.enableAutoRegister`、`ThreadMonitorConfig.enableThreadLeakDetect` / `threadLeakThresholdMs`、`RenderConfig.detectOverdraw` / `viewDrawThresholdMs`、`IoConfig.enableAutoHook`、`FpsConfig.windowSize`、Battery/FPS/GC/Render 的无源 `maxStackTraceLength`、`ApmWireProtocol.ENVELOPE_SCHEMA_VERSION` 及 `ReferenceChainAnalyzer` 相关类型）已在 `0.2.0` 按 [API 兼容策略](API_COMPATIBILITY.md) 移除，不再保留无运行时作用的公开配置字段。

## 十一、构建与发布

2026-11-22 将项目的 Gradle daemon、测试工具链和 Java/Kotlin 字节码目标迁移至 JDK 21，并将 AGP 升级到 9.5.2。Android Studio Sync、全模块 Debug 构建、benchmark Release 与 AndroidTest Kotlin 编译通过。样例 APK 元数据记录 AGP 9.5.2，已核验 Debug AAR 中的实现类为 Java 21 字节码。

Android Studio 同步会解析依赖的 Javadoc 和示例源码，这些文件也受 Gradle 依赖校验约束。2026-11-22 对照 Maven Central 与 Google Maven 官方原件核对 SHA-256 后，将本次同步所需的校验和登记到根 `gradle/verification-metadata.xml`，并在 Android Studio 重跑完整同步通过。新增缺失记录前，应查看 Gradle 校验报告并确认本机缓存与官方原件一致。

根构建统一 group/version、POM 元数据、sources JAR/AAR 和可选 signing。主构建、`apm-plugin`、`build-logic` 与独立 Maven consumer 均使用 JDK 21 运行 Gradle daemon，并以 Java 21 toolchain 编译和测试；Android、纯 JVM、Gradle 插件与 consumer 的 Java/Kotlin 字节码目标统一为 21。构建链为 Gradle 9.6.0 / AGP 9.5.2 / Kotlin 2.2.21（AGP 9 内置 Kotlin，`jvmTarget` 跟随 `compileOptions.targetCompatibility`），`compileSdk` 为 36（`targetSdk` 仍 34）；发布 AAR 的 `minCompileSdk=36` 与插件的 AGP 9.5.2 依赖把接入方下限定为 AGP 9.x、JDK 21 与 `compileSdk ≥ 36`，Kotlin 元数据保持 2.2.0。仓库以 `.java-version` 声明推荐 JDK 21，`settings.gradle.kts` 在项目配置前拒绝低于 21 的 Gradle runtime；`python tools/verify_ci.py` 是平台无关的完整客户端门禁，会检查 Java、23 个根发布制品与 included `apm-plugin` 的公开 ABI、四个 Gradle build root 的依赖 SHA-256 元数据、25 坐标发布候选、device-lab plan 和 benchmark host tests，并强制重跑根 Android/model、included plugin 和文档校验。Kotlin binary-compatibility-validator 0.18.2 从 Android Release/JVM 产物生成已提交基线；24 个制品中除不承载实现类的 `apm-bundle` 外均要求非空，sample/benchmark 明确排除。版本、基线更新和门禁边界见 [API 兼容策略](API_COMPATIBILITY.md)，制品签名、SBOM、凭据和外部仓库边界见 [发布与供应链门禁](RELEASE_PROCESS.md)。`build-logic` 收敛发布型 Android library 的 compileSdk/minSdk/Java 版本；`apm-bundle` 不承载实现类，通过 `api(project(...))` 生成传递依赖 POM，为完整能力接入提供单一坐标；`apm-benchmark` 直接应用官方 Benchmark 插件并明确排除 Maven publication。`verifyReleasePerformanceBudgets` 把 connected microbenchmark 与 fail-closed verifier 串联；`run_device_soak.py` 生成显式物理机工件，`verifyDeviceSoakFromResults` 只验证显式 profile/result，不搜索旧文件；`verifyDeviceLabMatrix` 只验证计划，`verifyDeviceLabCoverageFromResults` 才验证全部显式 schema-v3 工件、exact provenance 和完整矩阵。`apm-plugin` 现在既作为 included build 独立测试，也独立发布实现坐标和 `com.apm.slow-method` marker。

发布候选校验逐个读取 AAR 元数据，要求 `minCompileSdk=36`，并校验 Java 21 class file major 65。历史构建与真机记录见 [历史验证记录](HISTORICAL_VERIFICATION.md)。

## 十二、测试策略

131 个测试/benchmark 文件（985 个 JUnit 用例 + 1 个 UiAutomator 场景驱动器，另加 5 个 Python 测试文件共 56 项检查）覆盖 strict profile/consent/活动与冷启动撤回、V2/V3 typed/resource/batch identity/byte split/exact ACK、V3 occurrence/native identity（含 trim 校验）与 V2 semantic-smuggling 拒绝、critical priority promotion 与子进程发射自监控计数、Crash 委托/fatal 边界、ANR 同步 hand-off 与原因分类（含 BINDER 先于 CPU 顺序回归）、IPC store 故障保留与重试、SQLite 关闭重开恢复、配置默认值、事件 identity/typed codec v1-v4/legacy Protobuf、dispatcher 单事件故障隔离/fatal 边界/条数与字节准入/多 victim 优先级淘汰/混合优先级 FIFO 语义/单模块高水位隔离与关闭开关/固定阶段延迟直方图、IPC pending/event/file/directory 字节预算与单一周期 writer、drop reason/priority/UNATTRIBUTED 归因、业务上下文和直接事件异步快照、单调 duration/expiry/dedup/rate-limit 与 epoch collector 时间、签名配置 canonical JSON/Ed25519/HTTP/ETag/LKG/过期/rollback/equivocation、动态 kill switch/采样/限流/endpoint/短期 Header、PII、聚合/指纹、durable outbox migration/lease/concurrency/固定种子状态机、uploader worker/scheduler 线程命名、daemon 与后台优先级、GC 分配/回收窗口、IO 吞吐窗口、SQLite QueryPlan gate/现代 SCAN 解析、SDK 诊断脱敏/JSONL/滚动/导出失败数据化/并发降级、宿主接入 registry 五态/并发/会话重置/late callback、Provider 自动初始化/no-op/错误隔离、Memory Reporter/OOM/Hprof 截断输入/ViewModel 引用/真实采样、Network 请求分类/聚合/phase 截断/HttpURLConnection 异常语义、JNI 静态绑定契约、ASM 正常/异常出口、Binder/线程池/WebView、FPS 实际 interval 定义与 FrameMetrics primitive rolling accumulator 核心计算、三个 AndroidX Microbenchmark 类、发布候选缺失/篡改/ZIP traversal/签名策略，以及 microbenchmark/device-soak/device-lab host gate 的通过、解析、聚合、时长、重启、功耗、超限、emulator、lane/profile 完整性、OEM/reset 多样性和 provenance 一致性分支。

测试通过不能代替以下验证：

- 真机 Native 行为与符号化
- 真机真正执行进程被杀/断电/磁盘满场景
- 物理设备 `24h` / `72h` 与长稳功耗、热和磁盘验收
- 真机跑满 24/72 小时的功耗、内存、离线和重启结果
- 多 OEM/Android 版本兼容
- 生产 PostgreSQL/TLS Collector 的并发、commit uncertainty 与 ACK 丢失故障注入

## 十三、客户端完成边界与外部工作

仓库内可完成的客户端缺口已收口：单依赖 `apm-bundle` 分发、strict production profile/显式 consent/撤回清理、V2 batch-declared compatibility、V3 occurrence-bound typed/resource/batch/size/ACK 契约、Crash/ANR 同步 critical hand-off、按 drop reason/priority 的损失证据、稳定事件身份、SQLite v4 additive migration、durable codec V4 与 V1/V2/V3 兼容读取、本地去重、claim/lease/expiry、owner-aware ACK、dispatcher/IPC/SQLite 跨层条数与字节预算、逐请求短期鉴权、签名配置/LKG/kill switch/采样/限流/endpoint、优先级感知入口背压与单模块高水位隔离、业务上下文同步契约与异步 LKG 缓存、带迟滞恢复的 AutoThrottle、默认隐私保护、运行时配置/payload/occurrence 快照、直接事件异步 map 冻结、epoch/单调时钟职责分离、显式 OkHttp/HttpURLConnection/Binder/WebView/线程池公共 API、按实际 interval 定义的 FPS、FrameMetrics 无逐帧对象分配滚动累计、`sdk_health` 双通道、自诊断、固定 microbenchmark 预算，以及 fail-closed 的真机 A/B/离线/重启 smoke/24h/72h gate 均已实现。手动与 Provider 自动初始化现在有明确互斥文档和生命周期测试；V3 只能用手动三参数初始化。sample 对 IO、SQLite、WebView、IPC、线程池与 Battery 使用真实显式 API，而不只注册模块。

本地 durable round-trip 通过 codec V4 恢复 V3 typed scalar 并保留 occurrence，旧 V1/V2 行仍读取为字符串，V3 行保持 typed fields 且 occurrence 缺失。legacy Line Protocol/standalone Protobuf 继续输出字符串 field map；`PROTOBUF_ENVELOPE_V2` 保留兼容，当前 strict `PROTOBUF_ENVELOPE_V3` 以独立 schema 提供 occurrence-bound wire，不能把 durable codec tag、V2 batch resource 或 legacy endpoint 混用。参考 Collector 已按冻结协议完成本地 V2/V3 exact ACK/eventId 去重/HMAC 联调；生产落地仍需 PostgreSQL/TLS 部署和故障验收。

生产 Collector 的 PostgreSQL/TLS/租户运维、查询聚合/告警/Dashboard、Native 后台符号化、外部 Maven staging/promotion 与下载复核、云端 runner 接线，以及预生产阶段的 24h/72h、功耗仪或 UID、热与磁盘验收，全部依赖外部系统或受控设备。长 profile 不阻塞当前客户端 SDK 迭代，但正式生产准入前仍须按原预算执行。唯一任务清单和验收条件由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 维护。

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
- [变更历史](CHANGELOG.md)记录版本变化，[历史验证记录](HISTORICAL_VERIFICATION.md)保留详细验收证据。
- `docs/AndroidAPM_第一性原理SDK评审_2026-07-21.md`：其他 AI 初始评审的源码闭环复核；按证据等级区分客户端架构、短时真机、长稳/OEM 和端到端产品准入，不作为生产验收证书
- `docs/APM_Review_2026-07-08.md`：历史评审与当前处置状态
- `docs/APM_Optimization_2026-07-08.md`：历史优化建议与落地状态
- `docs/architecture/generated-diagrams/`：由当前架构同步生成的 SVG/PNG
- `docs/APM_对比报告.docx`、`docs/APM_框架对比报告.docx`：可分发报告产物
- 历史原始附件已移除；当前事实来源为源码和维护中的 Markdown 文档
