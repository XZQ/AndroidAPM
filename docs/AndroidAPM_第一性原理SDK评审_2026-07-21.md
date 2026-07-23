# AndroidAPM SDK 第一性原理评审

> 原始评审材料：2026-07-21，由其他 AI 梳理｜源码闭环复核：2026-07-23
> 评审性质：**代码与架构质量评审 + 客户端改进闭环复核**（不是生产验收证书，也不是业务 App 的运行时问题诊断）
> 验证方式：交叉阅读架构文档与当前源码，并执行 root/model/storage/plugin/benchmark 定向验证；源码与文档冲突时以源码和可执行结果为准。
> 当前基线：`develop` 分支，27 个构建单元，164 个主源码文件，102 个测试/benchmark 文件。2026-07-22 同一源码完整刷新通过根 Android 96 suites / 636 tests、model 5 suites / 46 tests、included plugin 1 suite / 18 tests，均为 0 failures/errors/skips；根 Android + model 为 101 suites / 682 tests，plugin 独立报告。2026-07-23 的物理 Redmi/Xiaomi `22041216UC` 上，AndroidX 三项 microbenchmark 预算通过。最初两轮 smoke 的 CPU `28.425%`、`32.046%` 超过 `20%`；定位并修复 FPS 静态页面 VSync observer 后，在未修改预算的前提下连续两轮以 `12.928%`、`12.362%` 全项通过，24h/72h 仍未执行。

## 0. 评审方法：拿什么尺子量

一个端上可观测性 SDK 的"第一性原理"不是"功能多不多"，而是下面 9 个约束维度。它们不是可以脱离证据直接打分的口号；每项结论都应说明是源码机制、自动化测试、短时真机、长稳真机还是端到端系统证据。

| # | 第一性原理 | 一句话判据 |
|---|---|---|
| P1 | 观察者开销可测量且**有界** | CPU/延迟/内存/磁盘/电量有预算，超压时可降级且不阻塞宿主 |
| P2 | 客户端投递语义明确，损失可归因 | durable hand-off 后 acknowledged at-least-once；重传、淘汰、过期和拒绝都有明确定义 |
| P3 | 隐私与安全**默认收敛风险** | 默认脱敏、配置可验证、长期密钥不进 APK；业务字段和法规仍需接入方治理 |
| P4 | 测量**方法学正确** | 指标定义无偏差、无自指误差 |
| P5 | 关键长期资源**有界** | 队列、缓存、文件、单事件和存储总量有条数或字节预算 |
| P6 | recoverable 故障对宿主 **fail-safe** | 可恢复异常不逃逸到业务调用栈，致命 VM 错误不伪装成普通丢包 |
| P7 | 模块化 / 关注点分离 | 依赖方向单一、可独立演进 |
| P8 | SDK **自身可观测** | 监控器自身退化能被发现 |
| P9 | 兼容与演进 | schema/codec 向后兼容、可灰度 |

本评审使用以下证据等级，避免把"有代码"写成"已生产验证"：

| 等级 | 含义 |
|---|---|
| E1 | 源码/配置存在，契约可以静态复核 |
| E2 | 确定性单元、集成、lint 或构建验证通过 |
| E3 | 短时物理设备验证通过 |
| E4 | 24h/72h、功耗、热与多 OEM/Android 版本长稳验证通过 |
| E5 | 生产 Collector、鉴权、幂等、查询、告警和运维闭环通过 |

---

## 1. 总评

**结论：这是一个客户端架构和失败边界已经成型的 Android APM SDK，但当前证据只支持"客户端源码闭环 + 短时真机通过"，不支持"完整生产验收通过"。** 核心链路（采集→分发→持久化→上传→远程配置）在文档和代码两层总体一致，outbox claim/lease、recoverable failure boundary、签名远程配置和跨层资源预算均有明确实现。R1–R12 以及后续 strict/wire/critical/time/byte-budget/physical-soak 客户端机制已经实现或采用明确有界方案；真实设备证明了三项 microbenchmark 和原预算 smoke，也真实暴露并关闭了 FPS observer CPU 缺口。24h/72h、长稳功耗、多 OEM/Android 版本和外部 Collector 闭环仍未验收。

原文的 `4.7 / 5.0` 没有公开权重、样本量和扣分公式，容易制造不必要的精确感，因此本次复核不再保留单一数字评分。更可复现的准入矩阵如下：

| 维度 | 当前证据 | 当前判定 |
|---|---|---|
| 客户端代码/架构 | E1 + E2 | 高成熟度；主要机制已有源码和自动化验证 |
| 短时物理性能 | E3 | 三项 microbenchmark 与两轮原预算 smoke 通过 |
| 长稳与设备兼容 | 未达到 E4 | 24h/72h、功耗/热、多 OEM/Android 版本未完成 |
| 端到端 APM 产品 | 未达到 E5 | Collector、服务端幂等、查询、告警和运维闭环在仓库外 |

九项原则的当前状态：

| 维度 | 当前状态 | 证据边界 |
|---|---|---|
| P1 观察者开销 | 短时通过 | microbenchmark + 两轮物理 smoke；长稳仍待验证 |
| P2 投递可靠性 | 客户端机制完成 | 本地 acknowledged at-least-once；服务端 eventId 去重和端到端验收未完成 |
| P3 隐私安全 | 默认机制完成 | 默认 PII 规则和 strict 校验不等于业务合规证明 |
| P4 测量方法学 | 核心语义已修正 | epoch/单调职责和 FPS 定义有测试；CPU A/B 字段已显式化，PSS/磁盘 control 与设备矩阵仍可增强 |
| P5 资源有界 | 关键客户端资源有预算 | 逻辑预算不等于 SQLite page/WAL、系统功耗或所有第三方自定义代码都有硬上限 |
| P6 宿主安全 | recoverable 边界较完整 | 不承诺任意 Native/VM/宿主自定义 uploader 故障都可隔离 |
| P7 模块化 | 已建立 | 内部分层独立，`apm-bundle` 仅提供分发入口 |
| P8 自身可观测 | 已建立双通道 | 独立 journal 仍可能受磁盘/权限故障影响，但失败被数据化 |
| P9 兼容演进 | 客户端契约已冻结 | legacy 保持不变；V2 Collector 兼容性仍需外部实现验证 |

另一个必须明确的配置事实是：`PRODUCTION_STRICT` 是显式 opt-in，`ApmConfig.runtimeProfile` 默认仍为 `COMPATIBILITY`。因此上表中的 strict 安全能力不能自动外推为所有默认初始化都处于生产严格模式。

---

## 2. 关键优势（含代码证据）

**A. 调用线程非阻塞，重活下沉且慢业务上下文可隔离（`Apm.kt` / `ApmDispatcher.kt`）**
`Apm.emit` 捕获发生时刻、线程名及 payload 快照后走非等待准入，map 合并/事件构建交给 `apm-dispatcher`。业务上下文默认同步模式要求 O(1)/无 IO/无等待锁；`ASYNC_CACHED` 则只读取不可变 LKG，provider 在 `apm-biz-context` 后台刷新。这是兼顾发生时语义、兼容性和延迟安全的设计。

**B. 条数 + 字节双预算队列和 offer-only 背压（`ApmDispatcher.kt` / `ApmEventSizeEstimator.kt`）**
`ArrayBlockingQueue<QueuedEvent>(2048)` 同时受默认 8 MiB estimated-retained budget 约束；调用线程只对冻结快照做保守大小估算，不执行 durable serialization。入队使用非等待 `tryLock` + `offer`，HIGH/CRITICAL 可在锁内淘汰足够数量的旧低优事件满足双预算，**永不等待 worker**。溢出是“按 reason 丢低价值事件”而非“让大事件把条数有界队列拖向 OOM”——符合 P1/P5。

**C. SQLite outbox 的 claim/lease 边界清晰（`SQLiteEventStore.kt:313-399`）**
- 写事务在 `SELECT` 候选行**之前**获取，杜绝多进程/多实例观察到同一批空闲行；
- `acknowledgeClaim` 用 `WHERE ... AND lease_owner = ?` 只对自有行删除；
- `failClaim` 只释放自有行并 `retry_count+1`；
- `pruneExpired` 跳过活动租约；
- 缓存计数 `decrementCachedCount` 用 `coerceAtLeast(0L)`，防止跨 store 删除造成负数绕过水位淘汰。
这是客户端本地 acknowledged at-least-once 的扎实实现；它不替代 Collector 按 `eventId` 去重，也不能把容量淘汰、重试耗尽或 TTL 清理描述成"数据永不丢"。

**D. Fail-safe 边界贯穿主要 recoverable 路径（`Apm.kt:80-91`）**
`runRecoverableBoundary` / `recordInternalErrorSafely` 把 `Exception` 隔离并双写自监控 + 独立诊断；**关键点：`catch (error: Exception)` 故意不捕获 `VirtualMachineError`/`OutOfMemoryError`**——致命 VM 错误不会被伪装成"丢包/重试"。独立诊断 journal 不依赖 dispatcher/outbox/uploader（`ApmDiagnostics` 在 `EventStore` 之前创建），初始化失败仍可导出本地证据。这是强有力的 P6 证据，但不能外推为 Native、系统强杀或任意宿主自定义代码都绝对安全。

**E. 签名远程配置加密卫生扎实（`RemoteConfigSignatureVerifier.kt`）**
Tink Ed25519 + 固定 32 字节 **pinned** raw key（非随响应下发）、`RAW` 输出前缀、对 **canonical JSON 字节**做 detached 验签；验签失败统一返回 `false` 不泄露"缺 key 还是签名错"；LKG 用 `SharedPreferences.commit()` 同步原子落盘（`RemoteConfigStore.kt:39-56`），保证进程死亡不会写出半个 revision。满足 P3。

**F. 资源硬上限几乎全覆盖（架构文档 §9）**
dispatcher 2048 条 + 8 MiB / drain 32 / IPC 4 MiB pending + 256 KiB raw event + 1 MiB file + 16 MiB ready directory / SQLite 50,000 行 + 64 MiB live payload + 256 KiB durable event / V2 HTTP envelope 默认 1 MiB、绝对 4 MiB / codec 2 MiB / map 4096 / big-number text 4096 字符 / 限流 LRU 256 / 非持久队列 500 / 诊断 200+256 记录各 4 MiB。普通 IPC 使用 lock-free pending 与单一周期 writer，ready 文件流式读取；溢出、拒绝和淘汰都有明确策略与健康计数。

**G. core/监控线程治理基本统一（`ApmExecutors.kt`）**
core 和监控模块通过 `ApmExecutors` 统一 daemon、`apm-` 前缀及后台 `MIN_PRIORITY` / 测量 `NORM_PRIORITY` 两级，便于 systrace/线程 dump 定位。`apm-uploader` 因依赖方向不能反向依赖 core，保留模块内 executor；当前 `RetryingApmUploader` 已用一个本地命名 factory 统一有序 worker 与 delayed-retry scheduler，显式设为 daemon / `Thread.MIN_PRIORITY`，并以实际 delegate 执行线程测试名称、daemon 和 priority。因而准确结论是“线程治理契约统一、实现入口按依赖层分为 `ApmExecutors` 与 uploader 本地 factory”，而不是强行让底层模块反向依赖 core。

**H. eventId 抗碰撞且跨编码稳定（`ApmEventIdGenerator.kt`）**
`UUID.randomUUID()` 作进程前缀 + `AtomicLong` 单调序列（base-16），进程内零碰撞，跨进程碰撞概率可忽略；且 eventId 在 Line Protocol / Protobuf / durable codec / SQLite / IPC 全程保留，供服务端幂等。

**I. Collector wire contract 已版本化冻结（`ApmBatchEnvelopeSerializer.kt` / `COLLECTOR_WIRE_V2.md`）**
legacy Line/standalone Protobuf 保持原字符串语义；独立 `PROTOBUF_ENVELOPE_V2` 用 field 15 显式区分 12 类标量，batch 携带 schema/SDK/fixed resource/retry-stable batchId，按实际编码字节拆批。2xx 只有 response schema、batchId、event count 精确匹配才允许 ACK/delete，partial ACK 明确不支持。

**J. 关键事件与真实损失都进入可证明边界（`emitCriticalSync` / `SdkDropReason`）**
Crash/ANR 绕过 shared queue、采样、聚合与限流，同步到 SQLite 或 critical IPC hand-off；较低调用 priority 自动提升为 CRITICAL，现场不做网络 IO。每次 drop 同时记录稳定 reason 和 priority；SQLite capacity eviction、retry/age prune 返回精确 priority，兼容 aggregate-only 结果进入 `UNATTRIBUTED`，不以 NORMAL 冒充未知。

---

## 3. 原始风险与当前处置（按初评优先级）

| 项 | 当前状态 | 闭环实现 | 主要提交 |
|---|---|---|---|
| R1 | Gate 已实现；microbenchmark 与原预算 smoke 通过，长稳未执行 | 固定 microbenchmark 预算 + 零 SDK control/SDK-enabled 启动与资源 gate；缺项/坏指标/时长不足/超限/emulator 均失败 | `c2eba30` + 当前源码 |
| R2 | 完成 | PII 默认开启；文本正则 + 高置信字段名直接遮蔽，包括数值敏感值 | `2823038` |
| R3 | 有界闭环 | 2048 条 + 8 MiB 双预算；75% 高水位后限制单模块 NORMAL/LOW 占总容量 50%，HIGH/CRITICAL 可多 victim 淘汰；单 worker 上限如实保留 | `b0ea7fa` + 当前源码 |
| R4 | 完成 | 同步 provider 契约化；新增 `ASYNC_CACHED`、LKG、合并刷新和生命周期 | `f2414df` |
| R5 | 完成 | durable codec v3 typed scalar、v1/v2 兼容、任意对象字符串 fallback、未知 tag 拒绝 | `0f4ecb8` |
| R6 | 完成 | 连续 3 个健康周期恢复，退化/迟滞周期重置 streak，恢复重查所有门禁 | `2823038` |
| R7 | 完成 | FPS 默认按 1 秒单调时间窗口上报，旧 `windowSize` 仅兼容 | `2823038` |
| R8 | 完成 | 主线程使用有界 primitive rolling accumulator，不再逐帧分配 report 对象 | `2823038` |
| R9 | 完成 | durable 单事件 256 KiB 软限 + 64 MiB 活跃 payload 总预算，单坏事件隔离 | `2823038` |
| R10 | 完成 | `sdk_health` 先写独立诊断 journal 数值摘要，再尝试 HIGH 事件 | `2823038` |
| R11 | 完成 | `apm-bundle` 单依赖传递暴露 22 个运行时 artifact，不承载实现类 | `bda7a7a` |
| R12 | 完成 | 显式 `traceHttpUrlConnection` wrapper，保持宿主结果/异常且不接管 body/disconnect | `ebf2239` |

### P0（建议尽快处理）

**R1. 开销预算 + CI 回归门——Gate 已实现，当前物理 smoke 已按原预算接受**
`apm-benchmark/benchmark-budgets.json` 固定 durable encode 30 µs/48 allocations、decode 60 µs/72 allocations、32-event SQLite batch 8 ms/2,048 allocations。`:apm-benchmark:verifyReleasePerformanceBudgets` 串联 connected benchmark 与 fail-closed verifier。新增 `device-soak-budgets.json`、sample A/B probe、host runner 与 verifier：SDK-disabled control 和 SDK-enabled/失败 uploader 冷进程构造相同 map，采启动、`Apm.init`、主线程 P95、CPU、PSS、app-private disk、UID power 与 thermal，并在 smoke/24h/72h profile 中强制实际时长与重启次数。长 profile 无 app UID 或外部功耗仪证据即失败。

2026-07-23 的物理 Redmi/Xiaomi `22041216UC` 上，正式 AndroidX runner 完成 3/3 测试，encode `4,640.93 ns / 22.00 allocations`、decode `4,841.81 ns / 46.00 allocations`、32-event SQLite `1,258,990.52 ns / 1,400.21 allocations`，三项预算均通过。初始两轮完整 smoke 分别得到 `28.425%` 与 `32.046%` CPU，连续超过 `20%`；稳定区间线程采样显示 enabled 主线程约 `26.4%`，control 主线程约 `0.8%`，根因是 FPS 在静态页面持续 repost Choreographer。改为 API 24+ FrameMetrics event-driven 主路径、注册失败或禁用时才回退 Choreographer 后，相同 APK SHA-256 的两轮 smoke 在原 `20%` 上限下以 `12.928%`、`12.362%` 全项通过。MIUI 的 Gradle session-based 安装仍被 OEM 拒绝，但直接安装同一测试 APK 后正式 runner 可执行；这应记录为安装器兼容问题，不能伪造成 Gradle 一键任务通过。

需要纠正 A/B 口径：历史 schema-v1 工件中，`startupDeltaMs` 使用 control 启动值，`cpuAveragePercent` 是 SDK-enabled segments 的绝对进程 CPU，不是 `enabled - control`；PSS 与磁盘同样报告 enabled campaign 内增长。当前源码已升级 result schema v2，新增 `cpuControlPercent`、`cpuEnabledAveragePercent` 和带符号 `cpuDeltaPercent`，校验器会从原始 jiffies samples 重算三者。现有绝对 `20%` CPU 门禁仍由 `cpuAveragePercent` 承担，预算没有放宽；旧 schema v1 工件继续兼容。由于当前仍是 campaign 前单一 control，delta 是归因辅助，不是跨 24h/72h 的配对因果估计。

**R2. PII 默认与字段级保护——已完成**
`enablePiiSanitization=true` 是当前默认值。dispatcher 在 durable encode 之前执行脱敏：手机号/邮箱/身份证/URL credential 等文本模式继续生效，高置信字段名（authorization、token、password、API key、cookie、phone/email 等）直接遮蔽整个值，因此数值型或不匹配正则的敏感值也不会漏过。关闭仍是显式 opt-out，并要求接入方隐私评审。

### P1（重要，规划处理）

**R3. 单 dispatcher worker 的共享容量爆炸半径——已做有界隔离与分阶段证据**
worker 仍顺序执行，这是明确保留的 ordering/线程安全取舍；项目没有虚称多 worker 吞吐。入口同时受 2048 条与默认 8 MiB 估算保留内存约束；总队列达到 75% 后，已占总容量 50% 的同一模块不能再写 NORMAL/LOW，给其他模块保留容量。HIGH/CRITICAL 绕过模块门禁，并可在 admission lock 内淘汰多个旧低优事件，直至条数和字节都满足。该方案解决 noisy-neighbor 与“大事件挤爆有界条数队列”的容量风险，不消除一次昂贵 lazy factory、聚合、脱敏或 store 操作造成的瞬时 head-of-line blocking。当前已在 self-monitor 开启时对 `resolve/sampling/aggregate/rateLimit/sanitize/storeHandoff` 记录固定桶 count/avg/P95 上界/max；关闭时零计时开销，开启时无逐样本对象分配。P95 是保守桶上界，store 按 batch，其他阶段按实际 invocation；这些证据尚未绑定阈值或自动并行。下一步必须先在真实 24h/72h 负载观察分布，再决定是否分区/并行并证明顺序语义。

**R4. `bizContext` 调用线程风险——已完成双模式**
`SYNCHRONOUS` 保留精确发生时快照与兼容性，但 KDoc/接入文档明确要求 O(1)、无 IO、无等待锁。`ASYNC_CACHED` 只在 emit 读取最近一次成功的不可变快照，provider 在 `apm-biz-context` 后台线程按有界周期运行；失败保留 LKG，显式 refresh 合并请求，stop 终止刷新。

**R5. durable codec 字段类型——已完成 v3，并以独立 wire V2 演进**
format v3 为 null、String、Boolean、Byte/Short/Int/Long、Float/Double、Char、BigInteger、BigDecimal 写显式 tag 并恢复原运行时类型；大数文本最多 4,096 字符。v1/v2 继续按历史 String 读取，未知 tag 只让损坏事件失败，任意对象通过 bounded `toString()` 降级而不启用危险对象反序列化。legacy Line/standalone Protobuf 仍为字符串，不被静默重解释；Collector typed fields 通过单独 V2 schema 提供。

**R6. Auto-throttle 自动恢复——已完成迟滞自愈**
退化立即且 sticky；恢复要求连续 3 个周期同时满足 drop rate <= 20% 与平均上传延迟 <= 3 秒。退化或迟滞区间会重置 streak；恢复时重新检查进程、动态签名配置与灰度门禁，注册/配置更新也不能绕过自动降级集合。

### P2（可优化，非紧急）

**R7. FPS 时间窗口——已完成**
`reportIntervalMs=1000` 默认使用单调时钟决定报告边界；deprecated `windowSize` 仅保留源码兼容，不再控制 cadence。FPS 使用相邻回调形成的真实 interval 数除以实际单调耗时，并按 refresh rate 封顶，同时输出 `windowDurationMs`；无帧时没有伪造 FPS 事件。

**R8. FrameMetrics 主线程逐帧分配——已完成 primitive rolling accumulator**
listener 只把 primitive long 写入固定容量 rolling accumulator；窗口快照时才构造报告对象。测试覆盖滚动淘汰、聚合结果与 API 26 delayed-frame 门禁，避免 120Hz 路径上的逐帧 report-object allocation。

**R9. durable payload 磁盘上限——已完成双预算**
codec 2 MiB 继续作为格式硬限；SQLite 默认单事件软限为 256 KiB，活跃 payload 总预算为 64 MiB，同时保留 50,000 行上限。编码/超限只拒绝单事件，同批有效 peer 继续事务落盘；trim 按低优先级、旧事件优先并保护活动 lease，拒绝/淘汰进入 SDK health。

**R10. `sdk_health` 共享 dispatcher——已完成双通道**
每个周期先把仅含数值计数的摘要写入独立 diagnostics journal，再尝试 HIGH 优先级普通事件。总 drop 同时按固定 `SdkDropReason` 和 priority 展开；未知 priority 显式标为 `UNATTRIBUTED`。dispatcher/outbox/uploader 拥塞不会抹掉本地摘要；journal 自身仍受 4 MiB 内存/队列与分片磁盘预算约束，且不会递归回流 logger。

**R11. 多 artifact 接入摩擦——已完成 `apm-bundle`**
当前 27 个构建单元保持内部模块化，`com.apm:apm-bundle:0.1.0` 的 POM 传递暴露 22 个运行时 SDK artifact，bundle AAR 不复制实现类，也不自动初始化模块或应用 `com.apm.slow-method`。体积敏感接入仍可选择细粒度 artifact。

**R12. HttpURLConnection 覆盖——已完成显式 wrapper**
`NetworkModule.traceHttpUrlConnection` 通过读取一次 `responseCode` 作为明确执行点，向宿主 block 交付 connection/status；不读取正文、不 disconnect、不伪装全局 hook。HTTP error 正常返回，transport `IOException` 保持原异常，非网络宿主异常不误报，report failure 不覆盖宿主结果，fatal VM error 保持可见。

---

## 4. 改进清单闭环结果

| 序 | 项 | 初评优先级 | 当前结果 |
|---|---|---|---|
| 1 | 固定开销预算 + benchmark gate（R1） | P0 | 已实现；物理 microbenchmark 与两轮原预算 smoke 通过 |
| 2 | PII 默认安全 + `fields` 字段级脱敏（R2） | P0 | 已实现并默认开启 |
| 3 | noisy-module 隔离与单 worker 边界（R3） | P1 | 已实现入口容量隔离；吞吐上限明确保留 |
| 4 | bizContext 契约化 / async cache（R4） | P1 | 已实现双模式、LKG 与 refresh 生命周期 |
| 5 | typed durable field schema（R5） | P1 | codec v3 兼容 v1/v2；legacy wire 不变，独立 typed wire V2 已冻结 |
| 6 | Auto-throttle 迟滞自愈（R6） | P1 | 已实现连续 3 健康周期恢复 |
| 7 | FPS 时间窗口（R7） | P2 | 已实现 1 秒默认单调窗口 |
| 8 | FrameMetrics 主路径降分配（R8） | P2 | 已实现 primitive rolling accumulator |
| 9 | payload 软上限/总量预算（R9） | P2 | 已实现 256 KiB/event + 64 MiB live payload |
| 10 | 健康事件独立证据（R10） | P2 | 已实现 journal summary + HIGH telemetry 双通道 |
| 11 | 单依赖消费者入口（R11） | P2 | 已实现 transitives-only `apm-bundle` |
| 12 | HttpURLConnection 接入（R12） | P2 | 已实现显式 wrapper 与异常边界测试 |
| 13 | 关键事件 hand-off + loss reason/priority | P1 | Crash/ANR 同步本地接管；drop 三维计数并显式保留 unknown |
| 14 | 时间语义 + 直接事件快照 | P1 | epoch/单调职责分离；异步 hand-off 冻结 fields/context/extras |
| 15 | dispatcher / multi-process / IPC / SQLite 字节预算 | P1 | 8 MiB dispatcher；4 MiB/256 KiB/1 MiB/16 MiB IPC；256 KiB/64 MiB SQLite |
| 16 | 真机 A/B / 离线 / 重启 / 长稳门禁 | P1 | Gate 已实现；两轮原预算 smoke 通过，24h/72h 与长稳功耗未执行 |

---

## 5. 继续完善清单（按执行顺序）

以下项目不能混写成一种"未完成"：1–3 是当前客户端仓库可继续完成的改进，4–8 需要长时间设备、Collector 或组织级合规资源。

| 顺序 | 改进项 | 为什么要做 | 完成判据 |
|---|---|---|---|
| 1 | 完善 device-soak CPU 归因字段 | **已完成**：schema v2 新增 control/enabled/delta，按 raw jiffies 重算；schema v1 兼容，绝对门禁不变 | 17 个 host tests 通过；下一次物理 campaign 才会产生 schema-v2 真机工件 |
| 2 | 统一 `apm-uploader` 本地线程策略 | **已完成**：模块内命名 factory 同时治理 worker/scheduler，显式 daemon / `Thread.MIN_PRIORITY`，依赖方向不变 | JDK 17 下 uploader 4 suites / 25 tests 通过，lint `No issues found` |
| 3 | 增加 dispatcher 分阶段尾延迟证据 | **已完成机制**：六个固定阶段使用无逐样本分配的有界直方图，关闭 self-monitor 时跳过计时 | JDK 17 下 core 27 suites / 197 tests 通过，lint `No issues found`；真机/24h 分布仍待采集 |
| 4 | 执行 24h 物理长稳 | 短 smoke 不能证明内存、磁盘、功耗、热和重启趋势；OnePlus Android 16 预检已证明 UID 功耗可采集 | 原预算、无 override、物理设备、>=24h、>=24 次进程启动、离线积压与功耗证据全部通过 |
| 5 | 执行 72h 与 OEM/Android 矩阵 | 单台 Redmi 的短时结果不能外推全部设备 | 原预算 72h 通过，并覆盖约定的低端/主流/高刷设备及 Android 版本矩阵 |
| 6 | 生产 Collector V2 闭环 | 客户端 eventId/ACK 机制不能自行证明服务端幂等 | V2 parser、鉴权/租户、exact whole-batch ACK、eventId 去重、协议错误与 dead-letter 通过断网/重传/重复批次测试 |
| 7 | 隐私与合规验收 | 默认规则只覆盖已知文本和高置信字段名 | 数据清单、字段分级、同意/撤回、多进程清理、日志/诊断导出、留存与删除策略由合规评审签字 |
| 8 | 故障注入与运维闭环 | 单元测试不能替代磁盘满、强杀、断电、Native/IPC/OEM 故障 | 明确场景矩阵、可重复工件、查询/告警可见、SDK health/drop reason 与服务端指标能够对账 |

生产准入必须同时满足 4–8 中约定的发布门槛；完成 1–3 只会提高客户端证据质量，不会自动改变生产准入状态。

---

## 6. 闭环验证与局限

- **组合测试**：JDK 17.0.14 下，根 `testDebugUnitTest --rerun-tasks` 通过 96 suites / 636 tests；`apm-model:test` 通过 5 suites / 46 tests；included `apm-plugin:test` 通过 1 suite / 18 tests，全部 0 failures/errors/skips。
- **定向测试/构建**：device-soak CPU 归因、OEM reset 回退与 profile-aware ADB transport 有界重试更新后 26 个 host tests 通过；smoke/long 默认重连窗口分别为 30/300 秒，绝对上限 600 秒。历史 schema-v1 物理 smoke 工件继续通过原绝对 CPU 门禁。uploader 线程治理更新后，JDK 17 下 4 suites / 25 tests 通过且 lint 为 `No issues found`。dispatcher 阶段证据更新后，core 27 suites / 197 tests 通过且 lint 为 `No issues found`，但尚未产生真机/24h 阶段分布。此前 sample debug APK/Debug lint 与 benchmark Release/AndroidTest Kotlin 构建通过，sample lint 为 0 errors / 25 warnings；cross-layer byte-budget、time-semantics、R5、R11 publication/consumer、R12 network、wire V2 与 critical hand-off 证据仍分别保留在项目/交接文档。
- **物理设备**：Redmi/Xiaomi `22041216UC` 上 AndroidX 3/3 microbenchmark 与 JSON 预算通过；FPS observer 修复后两轮 smoke 以 CPU `12.928%`、`12.362%` 通过原 `20%` 上限。OnePlus `PLK110`（Android 16）在限定包卸载重装回退后生成 schema-v2 工件，以 enabled CPU `6.161%`、UID 功耗 `29.062 mAh/hour` 通过；只读重试加入后的复验以 enabled CPU `5.134%`、UID 功耗 `29.423 mAh/hour` 再次通过，retry count 为 0。首次 24h 在第一小时内因 OnePlus 持续离线失败，无 JSON/接受结论。随后 Redmi 当前代码 smoke 以 CPU `11.319%`、window 30 秒、retry 0 通过，但无 UID power，故未启动长 profile。MIUI session install 与 OnePlus `pm clear` 权限问题均和 SDK 预算结果分开记录。
- **文档验证**：`python docs/verify_docs.py` 通过 43 个 Markdown 文件与 49 个本地链接。
- **仍需真机/外部系统**：执行 24h/72h、功耗/热/长稳、弱网/断电、Native/IPC/OEM 矩阵，以及生产 Collector/幂等/查询告警、外部 Maven 与云端 runner。host tests、模拟器、microbenchmark 或短 smoke 都不能替代长稳验收。

---

## 7. 一句话结论

**客户端实现已经形成可验证的跨层闭环：dispatcher/IPC/SQLite 分别按本层资源维度限界，默认 PII 保护、noisy-module/业务上下文/FPS/FrameMetrics/健康证据均有明确方案，durable 字段类型、Bundle 与 HttpURLConnection 接入也已落地；microbenchmark 证明 codec/SQLite 三项热路径低于既定预算，物理 smoke 也在不放宽绝对 CPU 门禁的前提下关闭了已发现的 FPS observer 问题。** 当前结论是"客户端架构成熟、短时真机通过、完整生产验收未完成"；正式上线前还需完成 24h/72h、Collector、合规、OEM 长稳和告警闭环。
