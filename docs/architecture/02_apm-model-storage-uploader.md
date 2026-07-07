# apm-model / apm-storage / apm-uploader 模块架构

> 数据模型、本地存储、上传通道

## 2026-07-07 优化更新

- `ProtobufSerializer` 补齐 field 13 `priority`（枚举名），与 Line Protocol 字段语义一致；`apm_event.proto` 已同步。
- `EventStore` 新增 `appendBatch`（默认逐条）；`SQLiteEventStore` 批量事务写入 + 缓存行数计数（每 512 次写入重同步）替代每条 COUNT(*)；`data` 列不再冗余存储 line protocol（`readRecent` 从 payload BLOB 解码渲染，旧行兼容）。
- `PendingEventStore` 新增 `pruneExpired(maxRetryCount, maxAgeMs)`（默认 no-op，SQLite 实现真实清理）。
- WAL 改用官方 `setWriteAheadLoggingEnabled(true)`：原 `execSQL("PRAGMA journal_mode=WAL")` 在现代 Android 直接抛异常（Robolectric 测试捕获的真实缺陷）。
- `HttpApmUploader`：读尽并关闭响应/错误流保住 keep-alive；解析 429/503 `Retry-After`（秒/HTTP-date）经 `ApmUploader.retryAfterHintMs()` 透出。
- `RetryingApmUploader` 退避改为独立调度线程延迟重投，失败批次不再阻塞整个上传队列。
- 新增 `UploaderLogger` 接口（apm-uploader 无法依赖 apm-core 的 ApmLogger），由宿主注入实现日志门控。
- `SQLiteEventStore` 获得 Robolectric 直接测试（9 例：批量/淘汰/outbox/重试/清理/坏行/golden readRecent）。

## 2026-07-04 实现状态

- `ApmEventCodec` 提供有版本、限长、可逆的二进制持久化格式。
- `PendingEventStore` 提供读取待上传行、成功确认删除、失败计数和队列长度接口。
- `SQLiteEventStore` 使用 schema v2 保存 payload，并按事件优先级与时间读取；损坏行会被隔离删除。
- `BatchApmUploader` 明确批量传输契约；`HttpApmUploader` 一批只发一个请求并支持正确的 Gzip header/body。
- 默认 endpoint HTTP uploader 通过 `ApmConfig.enableHttpGzip` 控制压缩，默认开启，可在集成侧显式关闭。
- `RetryingApmUploader` 使用硬容量和优先级淘汰，关闭时有界排空。
- 默认 SQLite 路径不会再套一层内存重试队列，避免重复缓冲和“已入队即当作上传成功”。

---

## apm-model 类图

```
┌──────────────────────────────────────────────────────────────┐
│               ApmEvent (data class)                          │
├──────────────────────────────────────────────────────────────┤
│ val module: String                                           │
│ val name: String                                             │
│ val kind: ApmEventKind                                       │
│ val severity: ApmSeverity                                    │
│ val timestamp: Long                                          │
│ val processName: String                                      │
│ val threadName: String    ← emit 时捕获，非构造时             │
│ val scene: String?                                           │
│ val foreground: Boolean?                                     │
│ val fields: Map<String, Any?>                                │
│ val globalContext: Map<String, String>                       │
│ val extras: Map<String, String>                              │
├──────────────────────────────────────────────────────────────┤
│ fun toLineProtocol(): String                                 │
│   格式: measurement|k1=v1,k2=v2|tag1=t1|timestamp           │
│   特殊字符转义: \n→\\n, |→\|, ,→\,                          │
└──────────────────────────────────────────────────────────────┘

┌───────────────────┐  ┌───────────────────────────┐
│ «enum» ApmEventKind│  │  «enum» ApmSeverity       │
├───────────────────┤  ├───────────────────────────┤
│ METRIC  (常规指标) │  │ DEBUG (调试)               │
│ ALERT  (告警)      │  │ INFO  (信息)               │
│ FILE   (文件事件)  │  │ WARN  (警告)               │
│                    │  │ ERROR (错误)               │
│                    │  │ FATAL (致命)               │
└───────────────────┘  └───────────────────────────┘
```

## apm-storage 类图

```
┌──────────────────────────────┐
│    «interface» EventStore    │
├──────────────────────────────┤
│ + append(event: ApmEvent)    │
│ + readRecent(limit): List<String>│
│ + clear()                    │
└──────────────┬───────────────┘
               │ 实现
┌──────────────▼───────────────┐
│     SQLiteEventStore         │
├──────────────────────────────┤
│ - dbHelper: EventDbHelper    │
│ - maxEvents: Int = 50_000    │
│ - payload: ApmEventCodec     │
│ - retry_count: Int           │
├──────────────────────────────┤
│ + append(event) @Synchronized│
│   ├── 写入 priority / payload │
│   └── 超过 maxEvents 时淘汰低优先级旧事件 │
│ + readPending(limit)         │
│   └── priority DESC, timestamp ASC │
│ + deletePending(ids)         │
│ + markRetry(ids)             │
│ + pendingCount()             │
└──────────────┬───────────────┘
               │ 兼容实现
┌──────────────▼───────────────┐
│     FileEventStore           │
├──────────────────────────────┤
│ - eventFile: File            │
│ - recentLines: ArrayDeque<String> │ (内存 ring buffer, 500行)
│ - rewriteScheduler: FileRewriteScheduler │
│ - initialized: Boolean @Volatile │
│ - maxLines: Int = 500        │
├──────────────────────────────┤
│ + append(event)  @Synchronized│
│   ├── lazy { 确保文件存在 }   │
│   ├── recentLines.addLast()  │
│   ├── 超过 maxLines 则 trim  │
│   └── 每累计50次 append rewrite 文件 │
│ + readRecent(limit)          │
│ + clear()  @Synchronized     │
└──────────────────────────────┘
```

## SQLite 持久化出箱流程

```
append(event)
    │
    ├── priority = StoragePriorityMapper.priorityOf(event)
    ├── payload = ApmEventCodec.encode(event)
    ├── INSERT INTO events(...)
    └── trimIfNeeded()
        └── 超过 maxEvents 时按 priority ASC, timestamp ASC 淘汰

PersistentUploadWorker
    │
    ├── readPending(batchSize)
    │   └── priority DESC, timestamp ASC
    ├── BatchApmUploader.uploadBatch(events)
    ├── 成功：deletePending(ids)
    └── 失败：markRetry(ids) + 指数退避后重试
```

## FileEventStore 流程

```
append(event)
    │
    ├── lazy 初始化（首次调用时）
    │   └── 创建 apm/events.log 文件
    │
    ├── lineProtocol = event.toLineProtocol()
    │
    ├── recentLines.addLast(line)
    │   └── while (size > maxLines) recentLines.removeFirst()
    │
    ├── rewriteScheduler.onAppend()
    │
    └── if (累计 append 次数 % 50 == 0)
        └── rewriteFile()  ← 全量重写，清理过期数据
```

## apm-uploader 类图

```
┌──────────────────────────────┐
│   «interface» ApmUploader    │
├──────────────────────────────┤
│ + upload(event: ApmEvent): Boolean │
│ + shutdown()                 │
└──────────────┬───────────────┘
               │
       ┌───────┴────────┐
       │ 实现             │ 实现
┌──────▼──────┐  ┌───────▼──────────────┐
│LogcatApm    │  │RetryingApmUploader   │
│Uploader     │  │  (装饰器模式)         │
├─────────────┤  ├──────────────────────┤
│- endpoint   │  │- delegate: ApmUploader│← 委托实际上传
│             │  │- queue: LinkedBlocking│  容量 500
│+ upload(e): Boolean│  │  Queue<ApmEvent>     │
│  → Log.d()  │  │- executor: SingleThread│
│  → 打印Line │  │- running: @Volatile  │
│    Protocol │  │- batchSize = 10      │
└─────────────┘  │- flushInterval = 30s │
                 ├──────────────────────┤
                 │+ upload(event)       │
                 │  └── queue.offer()   │← 非阻塞
                 │+ shutdown()          │
                 └──────────────────────┘
                         │ 持有
                 ┌───────▼──────────┐
                 │  RetryPolicy     │
                 ├──────────────────┤
                 │ maxRetries: 3    │
                 │ baseDelayMs: 1s  │
                 │ maxDelayMs: 30s  │
                 │ backoff: 2.0     │
                 │ delayForAttempt: │
                 │  base × backoff^n│
                 └──────────────────┘
```

`HttpApmUploader` 是默认 HTTP endpoint 路径的批量实现：一批事件写入一次请求，`enableHttpGzip=true` 时在输出 body 前设置 `Content-Encoding: gzip` 并压缩 payload。

## 上传重试流程

```
upload(event) 调用
       │
       └── queue.offer(event)  ← 非阻塞，队列满则丢弃
                │
                ▼ (上传线程循环)
       ┌───────────────────────────┐
       │  uploadWorker loop:        │
       │                           │
       │  ① queue.poll(30s)        │← 等待新事件
       │                           │
       │  ② batch.add(event)       │
       │     while (batch < 10)    │
       │       queue.poll(100ms)   │← 批量聚合
       │                           │
       │  ③ for (event in batch)   │
       │       delegate.upload()   │← 返回 false 或抛异常都视为失败
       │                           │
       │  ④ 失败则 retry           │
       │     delay = policy.delay  │
       │     Thread.sleep(delay)   │← 指数退避
       │     1s → 2s → 4s (max 30s)│
       └───────────────────────────┘
```
