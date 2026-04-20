# apm-model / apm-storage / apm-uploader 模块架构

> 数据模型、本地存储、上传通道

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
