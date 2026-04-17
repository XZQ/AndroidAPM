# apm-core 模块架构

> 核心框架层：初始化、模块注册、事件分发、限流、灰度

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
│ + start()                                                       │
│ + stop()                                                        │
│ + emit(module, name, kind, severity, fields)                    │
│ + isInitialized(): Boolean                                      │
│ + recentEvents(limit: Int): List<String>                        │
├─────────────────────────────────────────────────────────────────┤
│ «class» State                                                   │
│  - context: ApmContext                                          │
│  - dispatcher: ApmDispatcher                                    │
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
│ debugLogging: Boolean = true                                 │
│ processStrategy: ProcessStrategy (MAIN_ONLY/ALL/DEDICATED)   │
│ defaultContext: Map<String, String>                           │
│ bizContextProvider: BizContextProvider                        │
│ rateLimitEventsPerWindow: Int = 10                            │
│ rateLimitWindowMs: Long = 60_000                              │
│ dynamicConfigProvider: DynamicConfigProvider                  │
│ grayController: GrayReleaseController?                        │
│ enableRetry: Boolean = true                                   │
│ maxRetries: Int = 3                                           │
│ retryBaseDelayMs: Long = 1000                                 │
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
       │   ├── store = FileEventStore(app)
       │   ├── baseUploader = LogcatApmUploader(endpoint)
       │   ├── uploader = RetryingApmUploader(baseUploader, retryPolicy)
       │   ├── rateLimiter = RateLimiter(events, window)
       │   ├── dispatcher = ApmDispatcher(store, uploader, rateLimiter, logger)
       │   └── context = ApmContext(app, config, processName, logger, dispatcher)
       │
       ├── state = State(context, dispatcher)
       │
       └── 返回
```

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
       │       ├── 本地存储
       │       │   └── store.append(event)
       │       │       → event.toLineProtocol()
       │       │       → FileEventStore.append() (ring buffer + 文件)
       │       │
       │       └── 上传
       │           └── uploader.upload(event)
       │               → RetryingApmUploader.upload()
       │               → queue.offer(event) (非阻塞)
       │               → 上传线程批量取出并上传
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
