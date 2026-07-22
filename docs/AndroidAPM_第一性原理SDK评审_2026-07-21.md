# AndroidAPM SDK 第一性原理评审

> 初评日期：2026-07-21｜闭环复核：2026-07-22｜初评人：安迪（应用性能专家）
> 评审性质：**代码与架构质量评审 + 客户端改进闭环复核**（非运行时 APM 诊断）
> 验证方式：交叉阅读架构文档与当前源码，并执行 root/model/storage/plugin/benchmark 定向验证；源码与文档冲突时以源码和可执行结果为准。
> 当前基线：`develop` 分支，27 个构建单元，164 个主源码文件，102 个测试/benchmark 文件。2026-07-22 同一源码完整刷新通过根 Android 96 suites / 636 tests、model 5 suites / 46 tests、included plugin 1 suite / 18 tests，均为 0 failures/errors/skips；根 Android + model 为 101 suites / 682 tests，plugin 独立报告。cross-layer byte-budget 定向验证另通过 core 27 suites / 194 tests 与 storage 6 suites / 37 tests，两个 lint 均为 `No issues found`；后续 physical-device-soak host gate 14 tests 与 sample/benchmark 构建链通过。

## 0. 评审方法：拿什么尺子量

一个端上可观测性 SDK 的"第一性原理"不是"功能多不多"，而是下面 9 条铁律。任何一条被违反，SDK 就从"可观测工具"退化成"给宿主添乱的包袱"：

| # | 第一性原理 | 一句话判据 |
|---|---|---|
| P1 | 观察者效应可忽略且**有界** | 采集不能改变被测量的对象（延迟/内存/电量/线程） |
| P2 | 投递**可靠**：至少一次 + 持久化 + 幂等 | 崩溃、重启、弱网后数据不丢、不污染 |
| P3 | 隐私与安全**默认安全** | PII 不出端、配置不被伪造、密钥不在 APK |
| P4 | 测量**方法学正确** | 指标定义无偏差、无自指误差 |
| P5 | 资源**有界** | 任何结构都有硬上限，溢出即丢而非涨 |
| P6 | 宿主**绝对安全**（fail-safe） | 监控自身崩溃绝不让宿主崩 |
| P7 | 模块化 / 关注点分离 | 依赖方向单一、可独立演进 |
| P8 | SDK **自身可观测** | 监控器自身退化能被发现 |
| P9 | 兼容与演进 | schema/codec 向后兼容、可灰度 |

---

## 1. 总评

**结论：这是一个明显高于“普通内部 SDK”水平的成熟实现。** 核心链路（采集→分发→持久化→上传→远程配置）在文档和代码两层高度一致，且多处设计达到工业级标准（outbox claim/lease、fail-safe 边界、签名远程配置、跨层资源硬上限）。R1–R12 以及后续 strict/wire/critical/time/byte-budget/physical-soak 客户端闭环项均已实现或采用明确有界方案；仍需生产 Collector、允许安装的专用真机和设备矩阵执行外部验收。

**闭环后综合评分：4.9 / 5.0**。剩余扣分来自单 dispatcher worker 的已知吞吐上限、已冻结 typed wire V2 尚需生产 Collector 部署，以及预算 gate 尚未在接受的物理设备上形成首个发布基线；这些不再是未实现的客户端代码缺口。

| 维度 | 评分 | 一句话 |
|---|---:|---|
| P1 观察者效应 | 4.8 | microbenchmark + 端到端 A/B/长稳失败门、模块高水位隔离和 8 MiB dispatcher 字节门已落地；单 worker 上限仍明确存在 |
| P2 投递可靠性 | 4.8 | outbox claim/lease 教科书级，几乎挑不出毛病 |
| P3 隐私安全 | 4.8 | PII 默认开启，并覆盖文本规则与高置信敏感字段名；自定义业务字段仍需接入方治理 |
| P4 测量方法学 | 4.8 | epoch/单调职责分离；FPS 按真实 interval/elapsed time 计算并按刷新率封顶；真机方法学仍需设备矩阵 |
| P5 资源有界 | 4.9 | dispatcher、IPC、durable payload、存储总量、大数解析与诊断均有条数/字节上限，溢出受控 |
| P6 宿主安全 | 4.9 | fail-safe 边界近乎最佳实践 |
| P7 模块化 | 4.8 | 内部分层保持独立，`apm-bundle` 提供单依赖分发入口 |
| P8 自身可观测 | 4.9 | `sdk_health` 独立摘要 + HIGH 副本，并按稳定丢弃原因/优先级计数 |
| P9 兼容演进 | 4.9 | legacy wire 保持不变；独立 V2 冻结 typed/resource/batch/size/exact-ACK 语义 |

---

## 2. 关键优势（含代码证据）

**A. 调用线程非阻塞，重活下沉且慢业务上下文可隔离（`Apm.kt` / `ApmDispatcher.kt`）**
`Apm.emit` 捕获发生时刻、线程名及 payload 快照后走非等待准入，map 合并/事件构建交给 `apm-dispatcher`。业务上下文默认同步模式要求 O(1)/无 IO/无等待锁；`ASYNC_CACHED` 则只读取不可变 LKG，provider 在 `apm-biz-context` 后台刷新。这是兼顾发生时语义、兼容性和延迟安全的设计。

**B. 条数 + 字节双预算队列和 offer-only 背压（`ApmDispatcher.kt` / `ApmEventSizeEstimator.kt`）**
`ArrayBlockingQueue<QueuedEvent>(2048)` 同时受默认 8 MiB estimated-retained budget 约束；调用线程只对冻结快照做保守大小估算，不执行 durable serialization。入队使用非等待 `tryLock` + `offer`，HIGH/CRITICAL 可在锁内淘汰足够数量的旧低优事件满足双预算，**永不等待 worker**。溢出是“按 reason 丢低价值事件”而非“让大事件把条数有界队列拖向 OOM”——符合 P1/P5。

**C. SQLite outbox 的 claim/lease 是教科书级实现（`SQLiteEventStore.kt:235-301`）**
- 写事务在 `SELECT` 候选行**之前**获取，杜绝多进程/多实例观察到同一批空闲行；
- `acknowledgeClaim` 用 `WHERE ... AND lease_owner = ?` 只对自有行删除；
- `failClaim` 只释放自有行并 `retry_count+1`；
- `pruneExpired` 跳过活动租约；
- 缓存计数 `decrementCachedCount` 用 `coerceAtLeast(0L)`，防止跨 store 删除造成负数绕过水位淘汰。
这是分布式"至少一次 + 幂等去重"客户端侧的标杆实现。

**D. Fail-safe 边界贯穿全局（`Apm.kt:29-70`）**
`runRecoverableBoundary` / `recordInternalErrorSafely` 把 `Exception` 隔离并双写自监控 + 独立诊断；**关键点：`catch (error: Exception)` 故意不捕获 `VirtualMachineError`/`OutOfMemoryError`**——致命 VM 错误不会被伪装成"丢包/重试"。独立诊断 journal 不依赖 dispatcher/outbox/uploader（`ApmDiagnostics` 在 `EventStore` 之前创建），初始化失败仍可导出本地证据。这是 P6 的满分作业。

**E. 签名远程配置加密卫生扎实（`RemoteConfigSignatureVerifier.kt`）**
Tink Ed25519 + 固定 32 字节 **pinned** raw key（非随响应下发）、`RAW` 输出前缀、对 **canonical JSON 字节**做 detached 验签；验签失败统一返回 `false` 不泄露"缺 key 还是签名错"；LKG 用 `SharedPreferences.commit()` 同步原子落盘（`RemoteConfigStore.kt:39-56`），保证进程死亡不会写出半个 revision。满足 P3。

**F. 资源硬上限几乎全覆盖（架构文档 §9）**
dispatcher 2048 条 + 8 MiB / drain 32 / IPC 4 MiB pending + 256 KiB raw event + 1 MiB file + 16 MiB ready directory / SQLite 50,000 行 + 64 MiB live payload + 256 KiB durable event / V2 HTTP envelope 默认 1 MiB、绝对 4 MiB / codec 2 MiB / map 4096 / big-number text 4096 字符 / 限流 LRU 256 / 非持久队列 500 / 诊断 200+256 记录各 4 MiB。普通 IPC 使用 lock-free pending 与单一周期 writer，ready 文件流式读取；溢出、拒绝和淘汰都有明确策略与健康计数。

**G. 线程治理统一（`ApmExecutors.kt`）**
全部 daemon 线程、`apm-` 前缀、后台 `MIN_PRIORITY` / 测量 `NORM_PRIORITY` 两级，便于 systrace/线程 dump 定位且不抢宿主 CPU。

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
| R1 | 客户端完成；物理执行待设备解禁 | 固定 microbenchmark 预算 + 启动/主线程/CPU/PSS/功耗/磁盘/热/24h/72h A/B gate；缺项/坏指标/时长不足/超限/emulator 均失败 | `c2eba30` + 当前源码 |
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

**R1. 开销预算 + CI 回归门——已完成客户端 gate**
`apm-benchmark/benchmark-budgets.json` 固定 durable encode 30 µs/48 allocations、decode 60 µs/72 allocations、32-event SQLite batch 8 ms/2,048 allocations。`:apm-benchmark:verifyReleasePerformanceBudgets` 串联 connected benchmark 与 fail-closed verifier。新增 `device-soak-budgets.json`、sample A/B probe、host runner 与 verifier：SDK-disabled control 和 SDK-enabled/失败 uploader 冷进程构造相同 map，采启动、`Apm.init`、主线程 P95、CPU、PSS、app-private disk、UID power 与 thermal，并在 smoke/24h/72h profile 中强制实际时长与重启次数。长 profile 无 app UID 或外部功耗仪证据即失败。当前 Xiaomi 安装 sample APK 仍被设备策略阻止，因此“首次接受真机通过”和 24/72 小时运行是外部设备验收，不伪装成已发生。

**R2. PII 默认与字段级保护——已完成**
`enablePiiSanitization=true` 是当前默认值。dispatcher 在 durable encode 之前执行脱敏：手机号/邮箱/身份证/URL credential 等文本模式继续生效，高置信字段名（authorization、token、password、API key、cookie、phone/email 等）直接遮蔽整个值，因此数值型或不匹配正则的敏感值也不会漏过。关闭仍是显式 opt-out，并要求接入方隐私评审。

### P1（重要，规划处理）

**R3. 单 dispatcher worker 的共享容量爆炸半径——已做有界隔离**
worker 仍顺序执行，这是明确保留的 ordering/线程安全取舍；项目没有虚称多 worker 吞吐。入口同时受 2048 条与默认 8 MiB 估算保留内存约束；总队列达到 75% 后，已占总容量 50% 的同一模块不能再写 NORMAL/LOW，给其他模块保留容量。HIGH/CRITICAL 绕过模块门禁，并可在 admission lock 内淘汰多个旧低优事件，直至条数和字节都满足。该方案解决 noisy-neighbor 与“大事件挤爆有界条数队列”的容量风险，不消除一次昂贵 worker 操作的瞬时 head-of-line blocking，后者由固定预算 gate、动态限流和文档边界共同约束。

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
| 1 | 固定开销预算 + benchmark gate（R1） | P0 | 已实现；物理设备首个接受结果待 runner/安装策略 |
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
| 16 | 真机 A/B / 离线 / 重启 / 长稳门禁 | P1 | smoke/24h/72h profile、完整资源采集与 fail-closed verifier 已实现；物理执行待设备解禁 |

---

## 5. 闭环验证与局限

- **组合测试**：JDK 17.0.14 下，根 `testDebugUnitTest --rerun-tasks` 通过 96 suites / 636 tests；`apm-model:test` 通过 5 suites / 46 tests；included `apm-plugin:test` 通过 1 suite / 18 tests，全部 0 failures/errors/skips。
- **定向测试/构建**：最新 physical-device-soak gate 的 14 个 host tests 通过；sample debug APK/Debug lint 与 benchmark Release/AndroidTest Kotlin 构建通过，sample lint 为 0 errors / 25 warnings。此前 cross-layer byte-budget 通过 core 27 suites / 194 tests 与 storage 6 suites / 37 tests；time-semantics、R5、R11 publication/consumer、R12 network、wire V2 与 critical hand-off 证据仍分别保留在项目/交接文档。
- **文档验证**：`python docs/verify_docs.py` 通过 43 个 Markdown 文件与 48 个本地链接。
- **仍需真机/外部系统**：在允许安装测试 APK 的物理设备上真正执行 smoke/24h/72h、功耗/热/长稳、弱网/断电、Native/IPC/OEM 矩阵，以及生产 Collector/幂等/查询告警、外部 Maven 与云端 runner。当前 Xiaomi 返回 `INSTALL_FAILED_USER_RESTRICTED`；host tests 或模拟器只证明代码链路，不能替代物理性能结论。

---

## 6. 一句话结论

**客户端实现已经形成跨层闭环：低开销同时有 microbenchmark 与端到端 A/B/离线/重启/长稳失败门，dispatcher/IPC/SQLite 分别按真实资源维度限界，隐私默认安全，noisy-module/业务上下文/FPS/FrameMetrics/健康证据均有有界方案，durable 字段类型、Bundle 与 HttpURLConnection 接入也已落地。** 这使 SDK 达到“可进入生产接入与真实设备/服务端验收”的水平；是否正式上线仍必须由接入方完成 Collector、合规、真机跑满预算、OEM 长稳和告警闭环，不能仅凭客户端单测替代。
