# apm-core 模块架构

> 同步日期：2026-07-10

## 1. 职责

`apm-core` 是 SDK 控制面和数据面入口：

- `Apm`：全局初始化、模块注册/停止、事件发射
- `ApmContext`：向模块暴露 Application/config/logger/emit
- `ApmDispatcher`：有界异步管线
- `PersistentUploadWorker`：durable outbox 单 worker 回放
- `ProcessEventCoordinator`：可选多进程文件 hand-off
- `UploaderFactory`：选择 custom/HTTP/Logcat 与 durable/non-durable 重试所有权
- throttle/aggregation/privacy/selfmonitor：横切保护能力
- `ApmExecutors`：SDK 线程工厂和优先级策略

## 2. 初始化

```text
Apm.init(application, config)
  synchronized(initLock)
  -> ignore duplicate init
  -> resolve processName
  -> MAIN_PROCESS_ONLY child process: skip
  -> create EventStore
       SQLITE -> SQLiteEventStore(EventDbHelper)
       FILE   -> FileEventStore + durability warning
  -> UploaderFactory.create(config, durableStore)
  -> optional RateLimiter
  -> optional EventAggregator
  -> optional PiiSanitizer
  -> optional SdkSelfMonitor
  -> ApmDispatcher
  -> optional ProcessEventCoordinator
  -> ApmContext / State
  -> start registered modules
```

`state` 只有基础设施组装完成后才发布。重复 `init` 为 no-op；`stop` 后可以重新初始化。

## 3. 模块生命周期与过滤

`register` 在 `init` 前后都可调用，模块名去重检查与插入由同一个 `initLock` 保护。

`startModule` 顺序：

1. 已启动同名模块：跳过
2. `ProcessModuleFilter`：MAIN/ALL/CUSTOM
3. `dynamicConfigProvider.getBoolean("apm.module.<name>.enabled")`
4. `GrayReleaseController.isEnabled("module.<name>")`
5. `onInitialize(context)`
6. `onStart()`
7. 加入 `startedModules`

启动异常被记录，不向宿主抛出。`stop` 只停止真正启动成功的模块。

## 4. emit 路径

`Apm.emit` 在调用线程执行：

- 读取当前 state；未初始化直接返回
- 捕获 `System.currentTimeMillis()`
- 捕获 `Thread.currentThread().name`
- 调用 `BizContextProvider.currentContext()` 取得快照
- 创建 lazy event factory 并非阻塞入队

event 的 map 合并和对象构建延迟到 dispatcher worker。子进程 IPC 需要完整 payload，因此会立即执行 factory。

`emitCriticalSync` 不走 lazy queue：立即构建、脱敏、同步 append 或同步 IPC publish，返回是否到达本地 hand-off point。

## 5. Dispatcher

### 队列与 worker

- `ArrayBlockingQueue<QueuedEvent>`
- capacity：2048
- producer：`offer`，永不等待
- overflow：drop + self-monitor
- worker poll：100 ms
- 每轮：首条 + `drainTo`，最多 32 条
- shutdown：不再接收，继续排空已接受事件，最多等待 3 秒

### 批处理顺序

```text
resolve lazy event
  -> EventAggregator.process (when enabled and not already aggregated)
  -> RateLimiter.tryAcquire
       ERROR/FATAL bypass
  -> PiiSanitizer.sanitize (when enabled)
  -> EventStore.appendBatch
  -> durable store: PersistentUploadWorker.signal
     non-durable store: uploader.upload each event
```

存储异常会把整批计入 drop，但不会让 worker 退出。

### 聚合维护

启用聚合时，`apm-aggregation` 定期 flush 过期窗口。聚合输出以 `preAggregated` 标记重新入队，避免二次聚合。关闭时剩余聚合数据直接写 store/交 uploader。

## 6. PersistentUploadWorker

worker 随 `PendingEventStore` 创建并立即开始循环：

```text
readPending(batchSize)
  empty -> pruneExpired -> wait signal or 30s
  non-empty -> upload once
      BatchApmUploader: one batch call
      ordinary uploader: all(event.upload)
    success -> delete ids + record latency
    failure -> markRetry ids
            -> max(local backoff, Retry-After)
            -> wait and reselect
```

单一重试权威位于 outbox worker；durable store 下 `UploaderFactory` 不再套 `RetryingApmUploader`，避免双队列/双重试。

限制：没有 claim/lease。多 worker 同时 `readPending` 会选择相同行，不能在未设计领取语义前并发化。

## 7. UploaderFactory

选择顺序：

1. `config.uploader`
2. `http://` / `https://` endpoint -> `HttpApmUploader`
3. 其他/空 endpoint -> `LogcatApmUploader`

非 durable store 且 `enableRetry=true` 时，外层使用 `RetryingApmUploader`。durable store 自己负责持久重试。

## 8. 多进程协调

`enableMultiProcessCoordination=false` 是默认值。

开启后：

- main process：dispatcher + scan executor
- child process：write executor，不直接调用 uploader
- 普通事件：buffer 100 行或 500 ms
- critical：同步单文件
- payload：`ApmEventCodec` 后 Base64，每行一个事件
- publish：unique `.tmp` -> `.ipc`
- scan：5 秒
- age limit：5 分钟

主进程 decode 后添加 `extras["ipc_source"]="remote_process"` 再进入 dispatcher。

## 9. 限流、灰度与动态配置

`RateLimiter`：

- key：`module/name`
- 默认 10 events / 60 seconds
- ERROR/FATAL bypass
- bucket map 维护 LRU，上限 256

`GrayReleaseController` 通过稳定 userId/sample rate 和 override 决定模块是否启用。`DynamicConfigProvider` 是接口，core 不绑定具体远程配置 SDK。

## 10. 聚合与隐私

聚合默认关闭：

- METRIC：窗口统计与有限样本
- ALERT：stack fingerprint 去重
- bucket/sample/cache 都有硬上限

PII sanitization 默认关闭。内置规则覆盖手机号、邮箱、身份证、URL token、password 等文本模式；生产环境需要结合自身字段和法规显式启用/扩展。

## 11. 自监控与降级

`SdkSelfMonitor` 记录：

- emit count
- drop count/rate
- queue size
- average/max upload latency
- internal error count

`Apm.recordInternalError` 为模块吞掉并降级的异常提供统一计数。`SdkHealthReport` 包含该计数，但当前 `Apm.createSelfMonitoringExecutor` 构造的 `sdk_health` 事件字段尚未带出 `internalErrorCount`。`AutoThrottle` 根据 drop rate/upload latency 停止低优先级，再在严重时停止 normal 模块。

当前限制：停用是本进程内单向动作，没有自动恢复；健康事件本身也经过同一 dispatcher。

## 12. ApmExecutors

- 统一 `apm-` 线程名前缀
- daemon thread
- `PRIORITY_BACKGROUND = MIN_PRIORITY`
- `PRIORITY_MEASUREMENT = NORM_PRIORITY`
- single-thread 与 scheduled executor 工厂

core/监控模块应使用该设施。`apm-uploader` 是下层模块，不能反向依赖 core，因此保留本地 executor。

## 13. Shutdown

1. `state=null`，切断新 emit
2. stop self-monitor/coordinator
3. `module.onStop`
4. dispatcher drain（3 秒）
5. flush aggregator residue
6. persistent worker shutdown 或 uploader shutdown
7. store close

这是有界优雅关闭，不承诺在系统强杀时运行；关键事件依赖同步 local hand-off 与下次进程重放。

## 14. 测试重点

- `ApmConfigTest`, `UploaderFactoryTest`
- `ApmDispatcherTest`
- `PersistentUploadWorkerTest`
- `ProcessEventCoordinatorTest`
- RateLimiter/Gray/DynamicConfig
- Aggregator/StackFingerprinter
- PII sanitizer
- SDK self-monitor

## 15. 已知限制

- 无 eventId/idempotency
- 无 concurrent outbox lease
- self-monitor health event 未形成独立控制平面
- 部分模块配置声明没有完整 runtime consumer
- PII/aggregation/multi-process 默认关闭，需要生产配置明确开启
