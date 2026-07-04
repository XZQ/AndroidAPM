# apm-core 模块架构

> 核心框架层：初始化、模块注册、事件分发、限流、灰度

## 2026-07-04 实现状态

- `ApmConfig.storageType` 默认 `SQLITE`，持久化存储使用独立 `PersistentUploadWorker` 回放和确认。
- `Apm.emitCriticalSync()` 用于崩溃等进程终止前事件；上传进程同步落盘，非上传进程同步发布 IPC 文件，不阻塞等待网络。
- `ProcessEventCoordinator` 使用 `.tmp` 写入和 `.ipc` 发布的单事件文件，扫描端只消费 ready 文件，避免删除半写入文件。
- `Apm.register()` 的同名检查与插入在同一锁内；只有初始化和启动成功的模块进入停止列表。
- 模块启动会实际消费动态开关 `apm.module.<name>.enabled` 和灰度控制结果。
- `Apm.stop()` 先阻止新事件，再排空 dispatcher，最后关闭 worker、uploader 与 store。
- SDK 健康报告周期输出 emit/drop/queue/latency，并可按 `AutoThrottle` 结果停止高开销模块。
- 聚合窗口由维护线程按固定延迟刷新，避免无后续事件时聚合桶永久滞留。
- `ApmConfig.enableHttpGzip` 控制默认 HTTP endpoint uploader 压缩行为，默认开启。

---

## 类图

```
┌─────────────────────────────────────────────────────────────────┐
│                        «object» Apm                             │
├─────────────────────────────────────────────────────────────────┤
│ - modules: CopyOnWriteArrayList<ApmModule>                      │
│ - state: State?  @Volatile                                      │
│ - initLock: Any                                                 │
├─────────────────────────────────────────────────────────────────┤
│ + init(application: Application, config: ApmConfig)             │
│ + register(module: ApmModule)                                   │
│ + stop()                                                        │
│ + emit(module, name, kind, severity, fields)                    │
│ + isInitialized(): Boolean                                      │
│ + recentEvents(limit: Int): List<String>                        │
├─────────────────────────────────────────────────────────────────┤
│ «class» State                                                   │
│  - context: ApmContext                                          │
│  - store: EventStore                                            │
│  - dispatcher: ApmDispatcher                                    │
│  - uploader: ApmUploader                                        │
└──────────────┬──────────────────────────────────────────────────┘
               │ 持有
               ▼
┌──────────────────────────┐     ┌──────────────────────────────┐
│      ApmContext          │     │     ApmDispatcher            │
├──────────────────────────┤     ├──────────────────────────────┤
│ + application: Application│     │ - store: EventStore          │
│ + config: ApmConfig      │     │ - uploader: ApmUploader      │
│ + processName: String    │     │ - rateLimiter: RateLimiter?  │
│ + logger: ApmLogger      │     │ - executor: ExecutorService  │
│ - dispatcher: ApmDispatcher│    ├──────────────────────────────┤
├──────────────────────────┤     │ + dispatch(event: ApmEvent)  │
│ + emit(event: ApmEvent)  │     │ + shutdown()                 │
└──────────────────────────┘     └──────────────────────────────┘

┌──────────────────────────┐     ┌──────────────────────────────┐
│  «interface» ApmModule   │     │    «interface» ApmLogger     │
├──────────────────────────┤     ├──────────────────────────────┤
│ + name: String           │     │ + d(message: String)         │
│ + onInitialize(ctx)      │     │ + w(message: String)         │
│ + onStart()              │     │ + e(message: String, t?)     │
│ + onStop()               │     └──────────────────────────────┘
└──────────────────────────┘               ▲
                                           │ 实现
                               ┌───────────┴──────────┐
                               │  AndroidApmLogger     │
                               │  (Logcat 输出)        │
                               └──────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    ApmConfig (data class)                     │
├──────────────────────────────────────────────────────────────┤
│ endpoint: String = ""                                        │
│ uploader: ApmUploader? = null                                │
│ debugLogging: Boolean = true                                 │
│ processStrategy: ProcessStrategy (MAIN_PROCESS_ONLY/ALL_PROCESSES/CUSTOM) │
│ customProcessModules: Map<String, List<String>>              │
│ defaultContext: Map<String, String>                           │
│ bizContextProvider: BizContextProvider                        │
│ rateLimitEventsPerWindow: Int = 10                            │
│ rateLimitWindowMs: Long = 60_000                              │
│ dynamicConfigProvider: DynamicConfigProvider                  │
│ grayController: GrayReleaseController?                        │
│ enableRetry: Boolean = true                                   │
│ maxRetries: Int = 3                                           │
│ retryBaseDelayMs: Long = 1000                                 │
│ enableHttpGzip: Boolean = true                                │
└──────────────────────────────────────────────────────────────┘
```

## 初始化流程

```
Application.onCreate()
       │
       ▼
Apm.init(app, config)
       │
       ├── synchronized(initLock)  ← 防止多线程竞态
       │
       ├── 检查 state == null（未初始化）
       │
       ├── 创建组件
       │   ├── logger = AndroidApmLogger()
       │   ├── store = SQLiteEventStore(...)（默认）或 FileEventStore(...)
       │   ├── uploader = UploaderFactory.create(config)
       │   ├── rateLimiter = RateLimiter(events, window)
       │   ├── dispatcher = ApmDispatcher(store, uploader, logger, rateLimiter, ...)
       │   └── context = ApmContext(app, config, processName, logger, dispatcher)
       │
       ├── state = State(context, store, dispatcher, uploader)
       │
       └── 返回
```

`UploaderFactory` 选择规则：

- `config.uploader != null`：直接使用显式注入 uploader
- `endpoint` 以 `http://` 或 `https://` 开头：使用 `HttpApmUploader`，并按 `enableHttpGzip` 设置压缩
- 其他情况：使用 `LogcatApmUploader`

## 事件分发流程

```
功能模块调用 Apm.emit(module, name, kind, severity, fields)
       │
       ├── 构建 ApmEvent
       │   ├── threadName = Thread.currentThread().name (调用线程捕获)
       │   ├── globalContext = 静态context + 动态context
       │   └── timestamp = System.currentTimeMillis()
       │
       ▼
ApmContext.emit(event)
       │
       ├── 非上传进程且开启多进程协调
       │   └── ProcessEventCoordinator.writeEvent(event)
       │       └── .tmp 写入完成后发布为 .ipc
       │
       ▼
ApmDispatcher.dispatch(event)
       │
       ├── executor.submit {    ← 异步单线程
       │       │
       │       ├── 限流检查
       │       │   ├── rateLimiter.tryAcquire("$module:$name")
       │       │   ├── ERROR/FATAL 跳过限流
       │       │   └── 超限则跳过本事件
       │       │
       │       ├── PII 脱敏
       │       │   └── piiSanitizer.sanitize(event)
       │       │
       │       ├── 本地持久化
       │       │   └── store.append(event)
       │       │       → SQLiteEventStore 写入 payload / priority / retry_count
       │       │
       │       └── 上传调度
       │           ├── PersistentUploadWorker.signal()（SQLite 默认路径）
       │           └── uploader.upload(event)（FileEventStore 兼容路径）
       │   }
       │
       └── 返回（非阻塞）
```

## 限流器内部结构

```
┌──────────────────────────────────────────────────┐
│              RateLimiter                          │
├──────────────────────────────────────────────────┤
│ buckets: ConcurrentHashMap<String, TokenBucket>  │
│ maxEventsPerWindow: Int (默认 10)                 │
│ windowMs: Long (默认 60_000)                      │
├──────────────────────────────────────────────────┤
│ tryAcquire(key: String): Boolean                  │
│   ├── bucket = buckets.getOrPut(key)              │
│   └── bucket.tryAcquire()                         │
│       ├── 当前时间 - startTime > windowMs?        │
│       │   └── 重置窗口                             │
│       ├── count < max?                            │
│       │   └── CAS(count, count+1) → true         │
│       └── return false (限流)                     │
└──────────────────────────────────────────────────┘
```
