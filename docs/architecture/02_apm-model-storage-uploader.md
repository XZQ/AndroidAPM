# apm-model / apm-storage / apm-uploader 架构

> 同步日期：2026-07-10

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
| identity | `module`, `name`, `kind` |
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
- 对 `|`, `,`, newline 做替换
- Map 按 key 排序
- 不是完整可逆 parser 协议

### Protobuf transport

- 零 protobuf runtime 依赖的 writer
- 单事件/批量序列化
- field 13 保存 priority enum name
- 适合 HTTP binary payload

### ApmEventCodec durable payload

- format version：1
- 用于 SQLite/IPC 的完整事件恢复
- payload 最大 2 MiB
- string 最大 1 MiB
- 每个 Map 最大 4096 项
- enum 未知值回退默认
- `fields` 通过 `Any?.toString()` 保存，重放后值类型为 String

因此 durable codec 保留事件结构，但不保留 arbitrary field 的原始数值/布尔类型。

## 4. EventStore 契约

```kotlin
interface EventStore {
    fun append(event: ApmEvent)
    fun appendBatch(events: List<ApmEvent>)
    fun readRecent(limit: Int): List<String>
    fun clear()
    fun close()
}
```

`appendBatch` 默认逐条调用；SQLite 覆盖为单事务批量写入。

`PendingEventStore` 额外提供：

- `readPending(limit)`
- `deletePending(ids)`
- `markRetry(ids)`
- `pendingCount()`
- `pruneExpired(maxRetryCount, maxAgeMs)`

它定义“上传成功后确认删除”的 durable outbox 契约。

## 5. SQLiteEventStore

### Schema

`EventDbHelper` 数据库版本 2：

```text
events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  priority INTEGER,
  module TEXT,
  name TEXT,
  severity TEXT,
  data TEXT,
  payload BLOB,
  timestamp INTEGER,
  retry_count INTEGER
)
```

v1 没有可逆 payload，升级到 v2 时直接重建表；旧行不能安全参加 acknowledged replay。

### 写入

- `append` 委托 batch path
- 一批在单个 SQLite transaction 中 insert
- `data` 对新行写空串，避免与 payload 双序列化
- cached row count 随增删维护
- 每 512 条 append 执行 COUNT(*) 重同步
- WAL 通过 `setWriteAheadLoggingEnabled(true)` 开启

### 读取与淘汰

- upload order：priority DESC, timestamp ASC
- capacity：50,000
- overflow eviction：priority ASC, timestamp ASC
- recent debug view：timestamp DESC，从 payload decode 后渲染 Line Protocol
- corrupted payload：记录 id 后隔离删除

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

它没有成功确认删除、重启 outbox 重放和 durable retry。core 初始化会输出清晰降级警告。不要把它画成默认生产主链路。

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
- 所有 header 在获取 output stream 前设置
- 2xx 为成功
- 429/503 解析 `Retry-After` 秒数或 HTTP-date
- 完整 drain/close response/error stream，允许 keep-alive 复用
- 网络异常返回 false 并 disconnect

当前接口没有 request event id、batch id 或服务端 ack token，HTTP 2xx 是唯一确认信号。

## 9. Durable retry

```text
readPending
  -> uploadOnce
  -> success: deletePending
  -> failure: markRetry
            -> delayForAttempt(max row retry + 1)
            -> max with retryAfterHint
            -> reselect later
```

没有内层 retry loop；outbox retry_count 是唯一重试权威。worker 空闲时执行 TTL/retry prune。

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
- priority ordering/eviction
- success acknowledgment by uploader boolean
- restart replay
- retry/TTL pruning
- batch/Gzip/Retry-After

缺口：

- eventId/idempotency key
- exactly-once server protocol
- concurrent claim/lease/expiry
- typed durable field schema
- authentication/tenant protocol
- collector compatibility/version negotiation

## 12. 测试

`apm-model`：Line Protocol、codec 边界/回放、priority、Protobuf。

`apm-storage`：File rewrite、priority mapper、Robolectric SQLite batch/eviction/outbox/retry/prune/corruption/recent。

`apm-uploader`：retry policy、priority comparator、Retrying uploader 容量/关闭、真实 HTTP socket/Gzip/batch/Retry-After。

`apm-core`：PersistentUploadWorker success/failure/fallback 与 UploaderFactory retry ownership。
