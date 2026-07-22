# apm-model / apm-storage / apm-uploader 架构

> 同步日期：2026-07-22

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
| occurrence | `timestamp`, `processName`, `threadName` |
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
- 对 `|`, `,`, newline 做替换
- Map 按 key 排序
- 不是完整可逆 parser 协议

### Protobuf transport

- 零 protobuf runtime 依赖的 writer
- 单事件/批量序列化
- field 13 保存 priority enum name
- field 14 保存稳定 eventId
- 适合 HTTP binary payload

### ApmEventCodec durable payload

- 当前写 format version：3；兼容读 version 1/2
- 用于 SQLite/IPC 的完整事件恢复
- codec payload 硬上限 2 MiB；SQLite 默认另施加 256 KiB durable 单事件软上限
- string 最大 1 MiB
- 每个 Map 最大 4096 项
- BigInteger/BigDecimal decimal text 最大 4096 字符，先检查再进入大数 parser
- enum 未知值回退默认
- version 3 每个 field 带类型 tag，原样恢复 null、String、Boolean、Byte、Short、Int、Long、Float、Double、Char、BigInteger、BigDecimal
- 其他 arbitrary object 不做对象序列化，继续通过 `toString()` 降级成 bounded String
- version 1/2 的 field 值按历史契约读取为 String；version 2 起保存 eventId，version 1 迁移行由 SQLite `event_id` 回填
- 未知 version-3 type tag 使单个损坏 payload 解码失败，由 storage 隔离坏行，不猜测长度或类型

因此客户端 durable round-trip 对受支持标量类型保真，同时避免 Java/Kotlin 任意对象反序列化。legacy Line Protocol 与 standalone Protobuf 仍通过 `toString()` 输出 `map<string,string>`，本地 format v3 不改变旧 Collector 契约。独立 `PROTOBUF_ENVELOPE_V2` 在 event field 15 写 `map<string,ApmTypedValue>`，是显式新协议而非 durable tag 的复用。

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

`appendBatch` 默认逐条调用；SQLite 覆盖为批量编码隔离 + 单事务写入。新增 result API 对旧自定义 store 有默认实现，并返回 `acceptedEventCount`、逐事件 `rejectedEvents` 与 `capacityEvictedEventCount`，供 dispatcher 把存储降级计入 SDK health。

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

它定义“上传成功后确认删除”的 durable outbox 契约。

## 5. SQLiteEventStore

### Schema

`EventDbHelper` 数据库版本 3：

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

v1 没有可逆 payload，升级到 v2 时直接重建表；旧行不能安全参加 acknowledged replay。v2 到 v3 使用 additive migration，保留 pending payload 并补 `legacy-<rowId>` eventId、空 owner 和 0 expiry。

### 写入

- `append` 委托带结果的 batch path；普通接口遇到单事件拒绝会抛出，dispatcher 使用 result API 做隔离
- 编码/codec 硬上限/256 KiB 软上限按单事件检查，坏事件加入 rejected，其余有效事件在单个 SQLite transaction 中 insert
- `event_id` UNIQUE + conflict-ignore 使重复追加幂等，缓存计数只增加真实 insert
- `data` 对新行写空串，避免与 payload 双序列化
- cached row count 与 live payload bytes 随增删维护；跨 store 删除造成的 stale delta 下限为 0
- 每 64 条成功 insert 重同步 `COUNT(*) + SUM(LENGTH(payload))`；缓存判断超限时先回读数据库真值，避免陈旧高估误删
- WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启

### 读取与淘汰

- upload order：priority DESC, timestamp ASC
- capacity：50,000 行 + 64 MiB live payload 逻辑预算；不包含 SQLite page/WAL 物理开销
- overflow eviction：任一维度超限时 priority ASC, timestamp ASC，返回实际淘汰数
- recent debug view：timestamp DESC，从 payload decode 后渲染 Line Protocol
- corrupted payload：记录 id 后隔离删除
- claim order：priority DESC, timestamp ASC；写事务覆盖 select + owner/expiry update
- prune/容量淘汰跳过尚未过期的活动 claim，因此可在 lease 释放/过期前临时超出逻辑预算

### 清理

`pruneExpired` 删除：

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
- 429/503 解析 `Retry-After` 秒数或 HTTP-date
- 完整 drain/close response/error stream，允许 keep-alive 复用
- 网络异常返回 false 并 disconnect

legacy 每个序列化事件包含 eventId，但没有 batch ID，HTTP 2xx 是整批唯一确认信号。V2 body 是 `ApmBatchEnvelope`：包含 schema/SDK、固定 resource、retry-stable batchId、repeated events 和 field-15 typed values；gzip 前完整 envelope 默认限制 1 MiB、绝对 4 MiB，按实际编码拆分，单事件超预算不打开连接。transport 拥有 `X-Apm-Schema-Version` / `Sdk-Version` / `Batch-Id` / `Event-Count`，宿主 Header 不能覆盖；response schema/batchId/eventCount 全匹配才成功，不支持 partial ACK。前一物理 batch 已 ACK、后一批失败时逻辑 claim 返回失败并可能整组重发，Collector 继续按 eventId 去重。规范见 [Collector Wire Protocol V2](../protocol/COLLECTOR_WIRE_V2.md)。动态凭据 provider 失败时返回 false，durable outbox 不删除该批；不会缓存并复用上一个可能已撤销的 Token。core strict built-in HTTP 在 factory 之前要求 HTTPS/V2/resource/batch headroom（显式非 Logcat custom uploader 除外）。同意撤回先停止 persistent worker/uploader，再调用 store clear；冷启动 overload 同时清理 SQLite、File 与 IPC artifacts。

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
- 不使用 `Thread.sleep`
- shutdown 有界 drain；尚未到期的内存 retry 会被放弃

这条路径是 best effort，不替代 SQLite outbox。

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
- typed durable scalar fields with version-1/2 compatibility
- V2 typed/resource/batch identity/encoded-byte split/exact ACK

外部/协议缺口：

- exactly-once server protocol and server-side eventId deduplication
- Collector 部署 V2 parser、exact whole-batch ACK、协议错误指标和 dead-letter
- Token 签发/刷新/撤销与租户授权服务（客户端逐请求注入已完成）
- collector compatibility/version negotiation

## 12. 测试

`apm-model`：Line Protocol、codec v1/v2 兼容、v3 scalar type/value round-trip、未知 tag/fallback、priority、legacy Protobuf、V2 typed/resource/batch identity。

`apm-storage`：File rewrite、priority mapper、Robolectric SQLite batch/row+payload-byte eviction/单事件隔离/outbox/retry/prune/corruption/recent、v2 additive migration、owner mismatch、expiry reclaim、双 store 并发 claim，以及固定种子 250 步 append/duplicate/claim/ACK/fail/release/expiry 状态机。

`apm-uploader`：retry policy、priority comparator、Retrying uploader 容量/关闭、真实 HTTP socket/Gzip/batch/Retry-After、逐请求 Token、Header 注入防护、HTTPS endpoint 轮换、V2 byte split 与 exact ACK。

`apm-core`：PersistentUploadWorker success/failure/fallback 与 UploaderFactory retry ownership。
