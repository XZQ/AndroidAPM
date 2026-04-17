# apm-thread-monitor 模块架构

> 线程监控：线程数膨胀 + 同名泄漏 + BLOCKED 死锁检测

---

## 类图

```
┌──────────────────────────────────────────────────┐
│           ThreadMonitorModule                      │
│       (implements ApmModule)                       │
├──────────────────────────────────────────────────┤
│ - config: ThreadMonitorConfig                    │
│ - apmContext: ApmContext?                         │
│ - mainHandler: Handler                           │
│ - monitoring: Boolean @Volatile                  │
│ - checkTask: Runnable (定时检查)                 │
├──────────────────────────────────────────────────┤
│ + onInitialize(context)                          │
│ + onStart() / onStop()                           │
│ + checkThreads()                                 │
│ + reportThreadCountSpike(count)                  │
│ + reportDuplicateThread(name, count)             │
│ + reportBlockedThreads(blockedThreads)           │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│  ThreadMonitorConfig (data class)         │
├──────────────────────────────────────────┤
│ enableThreadMonitor: true                 │
│ threadCountThreshold: 100                 │
│ duplicateThreadThreshold: 5               │
│ checkIntervalMs: 30_000 (30s)             │
│ maxStackTraceLength: 4000                 │
│ enableThreadPoolMonitor: true              │
│ enableThreadLeakDetect: true               │
│ queueBacklogThreshold: 100                 │
│ threadLeakThresholdMs: 300_000 (5min)     │
└──────────────────────────────────────────┘
```

## 检测流程

```
checkThreads() (每 30s 执行一次)
       │
       ├── threads = Thread.getAllStackTraces()
       │
       ├── 1. 线程数膨胀检测
       │   └── threads.size >= threadCountThreshold (100)
       │       → reportThreadCountSpike(count)
       │       → emit("thread_count_spike", WARN)
       │       fields: { count, threshold }
       │
       ├── 2. 同名线程检测
       │   └── groupByName()
       │       └── 同名 >= duplicateThreadThreshold (5)
       │           → reportDuplicateThread(name, count)
       │           → emit("duplicate_thread", WARN)
       │           fields: { threadName, count, threshold }
       │
       └── 3. BLOCKED 线程检测
           └── filter { state == Thread.State.BLOCKED }
               ├── 收集 BLOCKED 线程及其堆栈
               └── reportBlockedThreads(blocked)
                   → emit("blocked_thread", ERROR)
                   fields: {
                     blockedCount,
                     threads: [{ name, stackTrace, lockName }]
                   }
```

## 死锁检测逻辑

```
┌──────────────────────────────────────────────────┐
│              BLOCKED 线程分析                      │
├──────────────────────────────────────────────────┤
│                                                  │
│  Thread.getAllStackTraces()                      │
│       │                                          │
│       ├── 筛选 state == BLOCKED 的线程            │
│       │                                          │
│       ├── 提取每个 BLOCKED 线程的:                │
│       │   ├── 线程名                              │
│       │   ├── 堆栈信息                            │
│       │   └── 等待的锁 (lockInfo)                 │
│       │                                          │
│       └── 上报:                                   │
│           ├── blockedCount (BLOCKED 线程数)       │
│           └── 每个线程的 name + stackTrace        │
│                                                  │
│  注意: 死锁判定需分析等待图 (wait-for graph)     │
│  当前检测 BLOCKED 状态，真正的死锁需进一步分析    │
│                                                  │
└──────────────────────────────────────────────────┘
```
