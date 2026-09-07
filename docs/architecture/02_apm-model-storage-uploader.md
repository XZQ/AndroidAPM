# apm-model / apm-storage / apm-uploader 架构

> 同步日期：2026-09-07

## 1. 分层关系

```text
apm-core
  ├─ api -> apm-model
  ├─ implementation -> apm-storage
  └─ api -> apm-uploader

apm-storage -> api apm-model
apm-uploader -> api apm-model
```

model 定义契约，storage 定义本地 ownership hand-off，uploader 定义外部传输。storage/uploader 不依赖 core，避免基础层反向耦合。

## 2. ApmEvent

| 字段组 | 字段 |
|---|---|
| identity | `eventId`, `module`, `name`, `kind` |
| urgency | `severity`, `priority` |
| occurrence | `timestamp`, `processName`, `threadName`；V3 另有 `ApmOccurrenceContext` release/build/installation/native snapshot |
| context | `scene`, `foreground`, `globalContext`, `extras` |
| payload | `fields` |

`ApmEventKind`：METRIC / ALERT / FILE。

`ApmSeverity`：DEBUG / INFO / WARN / ERROR / FATAL，表达问题严重程度。

`ApmPriority`：LOW / NORMAL / HIGH / CRITICAL，表达存储、上传和资源竞争时的保留顺序。

## 3. 三种编码用途

### Line Protocol

- 人可读、Logcat/HTTP text payload
- 单行 `key=value|...`
- `eventId` 为独立字段
- 对 `|`, `,`, newline 做替换；单趟扫描实现，不含保留字符时返回原引用，输出与链式三趟 replace 逐字节一致
- Map 按 key 排序
- 不是完整可逆 parser 协议

### Protobuf transport

- 零 protobuf runtime 依赖的 writer
- 单事件/批量序列化
- field 13 保存 priority enum name
- field 14 保存稳定 eventId
- V2/V3 typed fields（field 15）的 map entry 由 writer 以 size-then-write 单趟写入，无两级中间缓冲；V3 额外要求 field 16 occurrence，V2 显式拒绝 occurrence semantic smuggling
- 适合 HTTP binary payload

### ApmEventCodec durable payload

- 当前写 format version：4；兼容读 version 1/2/3
- 用于 SQLite/IPC 的完整事件恢复
- codec payload 硬上限 2 MiB；SQLite 默认另施加 256 KiB durable 单事件软上限
- string 最大 1 MiB
- 每个 Map 最大 4096 项
- BigInteger/BigDecimal decimal text 最大 4096 字符，先检查再进入大数 parser
- enum 未知值回退默认；解码侧枚举名查找使用预建的 name→value map，不再逐次克隆枚举数组
- version 3 每个 field 带类型 tag，原样恢复 null、String、Boolean、Byte、Short、Int、Long、Float、Double、Char、BigInteger、BigDecimal；version 4 在 eventId 后 append occurrence/native frame snapshot
- codec 直接调用的兼容行为仍把 arbitrary object 通过 `toString()` 降级成 bounded String；正常 SDK 管线已在入口将未知对象替换为 `[unsupported]`，仅有界复制可变文本后脱敏，不能绕过 privacy 再转换明文
- version 1/2 的 field 值按历史契约读取为 String；version 2 起保存 eventId，version 1 迁移行由 SQLite `event_id` 回填；version 3 typed 行继续可读且 occurrence 保持缺失
- 未知 version-3 type tag 使单个损坏 payload 解码失败，由 storage 隔离坏行，不猜测长度或类型
- 解码只接受最短形式的合法 Unicode scalar UTF-8，拒绝 overlong、surrogate、越界 code point、截断和尾随字节；全 ASCII 字节序列跳过完整验证器（ASCII 必然是合法 UTF-8），接受/拒绝行为不变
- length/map count 必须能由剩余 payload 的最小编码承载后才分配；编码缓冲区在超过 2 MiB 前立即拒绝，且初始容量按事件字符数下限预估，避免默认 32 字节缓冲的多次扩容复制
- `stableBatchId` 的 16 字节摘要以查表方式转为小写 hex，替代逐字节 `String.format`；输出与 `%02x` 逐字符一致

因此客户端 durable round-trip 对受支持标量类型保真，并在 V4 保存 occurrence，同时避免 Java/Kotlin 任意对象反序列化。legacy Line Protocol 与 standalone Protobuf 仍通过 `toString()` 输出 `map<string,string>`，本地 format V4 不改变旧 Collector 契约。`PROTOBUF_ENVELOPE_V2` 在 event field 15 写 `map<string,ApmTypedValue>` 并保留 batch-declared 身份；`PROTOBUF_ENVELOPE_V3` 额外写 field 16 occurrence，是当前 strict contract。两者都是显式 wire schema，不复用 durable format tag。

## 4. EventStore 契约

```kotlin
interface EventStore {
    fun append(event: ApmEvent)
    fun appendWithResult(event: ApmEvent): EventStoreAppendResult
    fun appendBatch(events: List<ApmEvent>)
    fun appendBatchWithResult(events: List<ApmEvent>): EventStoreAppendResult
    fun readRecent(limit: Int): List<String>
    fun clear()
    fun close()
}
```

`appendBatch` 默认逐条调用；SQLite 覆盖为批量编码隔离 + 单事务写入。result API 对旧自定义 store 有默认实现，并返回 `acceptedEventCount`、逐事件 `rejectedEvents`、`capacityEvictedEventCount` 与可用时的 `capacityEvictedPriorityCounts`，供 dispatcher 把存储降级按 reason/priority 计入 SDK health。

`PendingEventStore` 额外提供：

- `readPending(limit)`
- `deletePending(ids)`
- `markRetry(ids)`
- `claimPending(ownerId, limit, nowMs, leaseDurationMs)`
- `acknowledgeClaim(ownerId, ids)`
- `failClaim(ownerId, ids)`
- `releaseClaims(ownerId)`
- `pendingCount()`
- `pruneExpired(maxRetryCount, maxAgeMs)`
- `pruneExpiredWithResult(maxRetryCount, maxAgeMs)`：SQLite 在同一删除事务内返回精确 priority counts；兼容实现保留 aggregate-only 结果

它定义“上传成功后确认删除”的 durable outbox 契约。可选 `DiscardablePendingEventStore.discardClaim(ownerId, ids)` 单独表达本地永久拒绝；SQLite 使用同一 owner predicate 和统计记账，不修改 schema。严格 V3 preflight 把无/非法 occurrence 的历史行单独拒绝并计数，不影响同批有效行的 ACK 或 retry_count。

`HttpApmUploader.shutdown()` 原子关闭请求 admission，并为当前活动连接发出异步 disconnect，避免某个平台阻塞取消锁拖住撤回线程。每个物理 split batch 再检查关闭状态；`isShutdownComplete()` 返回活动 HTTP 操作是否已退出。不能撤回已送达服务端的数据，后台取消与 SDK worker 退出证据需区别处理。

## 5. SQLiteEventStore

### Schema

`EventDbHelper` 数据库版本 4：

```text
events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  priority INTEGER,
  module TEXT,
  name TEXT,
  severity TEXT,
  data TEXT,
  payload BLOB,
  event_id TEXT UNIQUE,
  timestamp INTEGER,
  retry_count INTEGER,
  lease_owner TEXT,
  lease_expires_at INTEGER
)
```

v1 没有可逆 payload，升级到 v2 时直接重建表；旧行不能安全参加 acknowledged replay。v2 到 v3 使用 additive migration，保留 pending payload 并补 `legacy-<rowId>` eventId、空 owner 和 0 expiry。v3 到 v4 同样 additive：只补 `idx_priority_desc_ts(priority DESC, timestamp ASC)` claim 排序索引，行与列不变；该混合方向排序此前既不能正向也不能反向利用 `idx_priority_ts(ASC, ASC)`，claim/readPending 每轮只能全表扫描加临时 B-tree 排序，新索引允许沿索引序扫描并在满足 limit 后提前停止。

### 写入

- `append` 委托带结果的 batch path；普通接口遇到单事件拒绝会抛出，dispatcher 使用 result API 做隔离
- 编码/codec 硬上限/256 KiB 软上限按单事件检查，坏事件加入 rejected，其余有效事件在单个 SQLite transaction 中 insert
- `event_id` UNIQUE + conflict-ignore 使重复追加幂等，缓存计数只增加真实 insert
- `data` 对新行写空串，避免与 payload 双序列化
- 批量插入复用单个 `ContentValues`，逐行 clear 重填，避免每行分配 HashMap 支撑的容器
- cached row count、live payload bytes 与持久化 AUTOINCREMENT 水位随本实例增删维护；跨 store 删除造成的 stale delta 下限为 0
- 每批 append 在写事务内检查 `sqlite_sequence`，每 64 条成功 insert 与每次 `pendingCount()` 比对索引覆盖的 `COUNT(*)` 和插入水位：行数发现普通增删，水位发现 delete + insert 后净行数不变的替换。漂移时由单条 SQL 在同一快照回读行数、payload 字节和水位；常规路径不做 BLOB 扫描。容量淘汰从事务内数据库真值减去实际删除量设置最终缓存，删除不完整时回读全量
- WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启

### 读取与淘汰

- upload order：priority DESC, timestamp ASC
- capacity：50,000 行 + 64 MiB live payload 逻辑预算；不包含 SQLite page/WAL 物理开销
- overflow eviction：任一维度超限时 priority ASC, timestamp ASC，返回实际淘汰数与可观测 priority counts
- recent debug view：timestamp DESC，从 payload decode 后渲染 Line Protocol

这两项 SQLite 字节限制是 durable 层的最后一道预算，不替代 core 的 8 MiB dispatcher estimated-retained budget 或 multi-process IPC 的 4 MiB pending / 256 KiB raw event / 1 MiB file / 16 MiB directory 限制。各层使用自己的实际资源度量：dispatcher 估算 retained memory，IPC 对 encoded/file bytes 做精确检查，SQLite 对 codec payload bytes 做事务内精确检查；不能把同一个近似值冒充所有层的真实占用。
- corrupted payload：记录 id 后隔离删除
- claim order：priority DESC, timestamp ASC，由 v4 的 `idx_priority_desc_ts` 索引支撑；写事务覆盖 select + owner/expiry update；payload 解码在写事务提交后执行（含坏行删除的第二个小事务），缩短 WAL 写锁持有时间，claim/ACK/at-least-once 语义不变
- `pendingCount()` 执行索引覆盖的 `COUNT(*)` 与轻量 `sqlite_sequence` 查询（上传 worker 每轮循环调用）；任一水位变化才以单条快照 SQL 校准 payload 字节，常规路径不附带 `SUM(LENGTH(payload))` 全表 BLOB 扫描
- `pruneExpiredWithResult` 用一次 `GROUP BY priority` 扫描同时取得优先级计数与行/payload 字节统计，再执行删除，替代旧的同条件三趟全表扫描
- prune/容量淘汰跳过尚未过期的活动 claim，因此可在 lease 释放/过期前临时超出逻辑预算

### 清理

`pruneExpiredWithResult` 删除并将原因计为 `OUTBOX_EXPIRED_OR_RETRY_EXHAUSTED`：

- retry_count ≥ caller threshold
- timestamp 早于当前时间减 maxAge

Persistent worker 传入 10 retries 和 7 days。

## 6. FileEventStore 兼容路径

File path 是显式 `StorageType.FILE` 的兼容/调试方案：

- 内存 ArrayDeque ring buffer
- 最大 500 行
- 每累计 50 次 append 全量 rewrite
- `readRecent` 返回 Line Protocol
- 不实现 `PendingEventStore`

它没有成功确认删除、重启 outbox 重放和 durable retry。core compatibility 初始化会输出清晰降级警告；`PRODUCTION_STRICT` 在资源创建前直接拒绝 FILE。不要把它画成默认生产主链路。

## 7. Uploader 契约

```kotlin
interface ApmUploader {
    fun upload(event: ApmEvent): Boolean
    fun retryAfterHintMs(): Long?
    fun shutdown()
}

interface BatchApmUploader : ApmUploader {
    fun uploadBatch(events: List<ApmEvent>): Boolean
}
```

`true` 表示上传成功或被可靠接管。对于 persistent worker，只有完整 batch 为 true 才会确认删除。

## 8. HttpApmUploader

- `HttpURLConnection` POST
- Line Protocol：`text/plain; charset=utf-8`
- Protobuf：`application/x-protobuf`
- 默认 core endpoint path 开启 Gzip
- 静态 Header 与逐请求 `HttpHeaderProvider` 合并，动态短期 Token 可刷新/撤销
- Header 名/值拒绝控制字符，且不能覆盖 Content-Type/Encoding/Length、Host 等 transport 语义
- 可选逐请求 endpoint；远程覆盖只接受无 user-info 的 HTTPS URL，异常回退 bootstrap 地址
- 所有 header 在获取 output stream 前设置
- legacy 格式以 2xx 为成功；V2 还要求 exact whole-batch ACK
- 429/503 解析 `Retry-After` 秒数或 HTTP-date；超大秒数做饱和乘法，再由 worker 限制为 60 秒
- 完整 drain/close response/error stream，允许 keep-alive 复用
- 网络异常返回 false 并 disconnect

legacy 每个序列化事件包含 eventId，但没有 batch ID，HTTP 2xx 是整批唯一确认信号。V2/V3 body 都是 `ApmBatchEnvelope`：包含 schema/SDK、固定 resource、retry-stable batchId、repeated events 和 field-15 typed values；V3 还要求每个 event 的 field-16 occurrence，并使用 schema byte 3 与 `b3-` prefix。gzip 前完整 envelope 默认限制 1 MiB、绝对 4 MiB，按实际编码拆分（serializer 每事件只编码一次，用单事件探针锚定固定组件大小后以精确增量字节判定拆分边界），单事件超预算不打开连接。transport 拥有 `X-Apm-Schema-Version` / `Sdk-Version` / `Batch-Id` / `Event-Count`，宿主 Header 不能覆盖；response schema/batchId/eventCount 全匹配才成功，不支持 partial ACK。schema/eventCount 只接受正整数的规范无符号十进制，拒绝前导零、正号、负号和溢出；batchId 逐字节匹配。前一物理 batch 已 ACK、后一批失败时逻辑 claim 返回失败并可能整组重发，Collector 继续按 eventId 去重。当前 strict 规范见 [V3](../protocol/COLLECTOR_WIRE_V3.md)，兼容规范见 [V2](../protocol/COLLECTOR_WIRE_V2.md)。动态凭据 provider 失败时返回 false，durable outbox 不删除该批；不会缓存并复用上一个可能已撤销的 Token。core strict built-in HTTP 在 factory 之前要求 HTTPS/V3/resource/occurrence/batch headroom（显式非 Logcat custom uploader 除外）；当前自动 Provider 无 occurrence-aware overload，不能承担 V3 初始化。同意撤回先停止 persistent worker/uploader，再调用 store clear；冷启动 overload 同时清理 SQLite、File 与 IPC artifacts。

## 9. Durable retry

```text
claimPending(owner, lease)
  -> uploadOnce
  -> success: acknowledgeClaim(owner)
  -> failure: failClaim(owner)
            -> prune immediately when retry_count reaches maxRetries + 1
            -> delayForAttempt(max row retry + 1)
            -> clamp(max with retryAfterHint, 10 ms, 60 s)
            -> reselect later
```

没有内层 retry loop；outbox retry_count 是唯一重试权威。`maxRetries` 表示首次尝试后的重试次数；失败路径在 `retry_count >= maxRetries + 1` 时立即 prune，空闲周期继续负责 TTL 和历史耗尽行清理。

## 10. RetryingApmUploader 兼容路径

仅用于非 durable store：

- `PriorityBlockingQueue`
- Semaphore 硬容量 500
- 满时仅允许更高优先级事件淘汰低优先级事件
- drain batch，delegate 为 Batch 时一批调用
- 失败批次由独立 scheduled executor 延迟重投
- worker 与 scheduler 由同一模块内命名 factory 创建，显式 daemon / `Thread.MIN_PRIORITY`
- 不使用 `Thread.sleep`
- shutdown 有界 drain；尚未到期的内存 retry 会被放弃

这条路径是 best effort，不替代 SQLite outbox。线程 factory 保持 `apm-uploader -> apm-model` 的依赖边界，不反向依赖 `apm-core`；稳定线程名为 `apm-upload-retry` 与 `apm-upload-retry-scheduler`。

## 11. 交付语义与缺口

当前：

- local durable hand-off
- stable eventId and local deduplication
- concurrent claim/lease/expiry and owner-aware ACK
- priority ordering/eviction
- success acknowledgment by uploader boolean
- restart replay
- retry/TTL pruning
- batch/Gzip/Retry-After
- durable format V4 occurrence + V3 typed scalar，并兼容读取 V1/V2/V3
- V2/V3 typed/resource/batch identity/encoded-byte split/exact ACK

本地参考服务端已完成 V2/V3 parser、提交后 exact whole-batch ACK、`(tenant_id,event_id)` 去重/冲突检测、occurrence persistence 与 installation HMAC。现场/生产缺口是 PostgreSQL 并发与 commit uncertainty、TLS ingress、代理丢 ACK、真实 dead-letter/retention 演练、Token 签发/刷新/撤销、协议版本下线与跨版本运营；不能用 test-only SQLite E2E 冒充这些验收。

## 12. 测试

`apm-model`：Line Protocol、codec V1/V2/V3 兼容、V4 occurrence/native round-trip、typed scalar type/value、未知 tag/fallback、priority、legacy Protobuf、V2/V3 typed/resource/batch identity 与 semantic-smuggling 拒绝。

`apm-storage`：File rewrite、priority mapper、Robolectric SQLite batch/row+payload-byte eviction/单事件隔离/outbox/retry/prune/corruption/recent、v2 additive migration、owner mismatch、expiry reclaim、双 store 并发 claim，以及固定种子 250 步 append/duplicate/claim/ACK/fail/release/expiry 状态机。

`apm-uploader`：retry policy、priority comparator、Retrying uploader 容量/关闭、worker/scheduler 实际执行线程的名称/daemon/priority、真实 HTTP socket/Gzip/batch/Retry-After（含溢出饱和）、逐请求 Token、Header 注入防护、HTTPS endpoint 轮换、V2/V3 byte split、media type 与 canonical exact ACK 固定语料。

`apm-core`：PersistentUploadWorker success/failure/fallback、UploaderFactory retry ownership、strict V3 occurrence 门禁，以及普通/critical/IPC hand-off 前的 occurrence 绑定与 Native frame 合并。
