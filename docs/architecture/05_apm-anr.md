# apm-anr 模块架构

> ANR 监控：SIGQUIT 信号 + Watchdog 双重检测 + traces.txt + 原因分类 + 去重

---

## 类图

```
┌─────────────────────────────────────────────────────────────┐
│                     AnrModule                                │
│            (implements ApmModule, ActivityLifecycleCallbacks)│
├─────────────────────────────────────────────────────────────┤
│ - apmContext: ApmContext?                                    │
│ - config: AnrConfig                                         │
│ - mainHandler: Handler                                      │
│ - anrDetected: AtomicBoolean                                │
│ - tick: AtomicBoolean                                       │
│ - running: Boolean @Volatile                                │
│ - watchdogThread: Thread?                                   │
│ - lastReportTimeMs: AtomicLong                              │
│ - lastStackFingerprint: String @Volatile                    │
│ - stackSamples: MutableList<String>                          │
│ - sampleHandler: Handler                                    │
├─────────────────────────────────────────────────────────────┤
│ + onInitialize(context)                                     │
│ + onStart()                                                 │
│ + onStop()                                                  │
│ - registerSigquitHandler(): Boolean                         │
│ - onSigquitReceived()                                       │
│ - startWatchdog()                                           │
│ - watchdogLoop()                                            │
│ - handleAnrDetection(source: String)                        │
│ - collectStackSamples(): List<String>                       │
│ - classifyAnrCause(mainStack, samples): String              │
│ - readTracesFile(): String                                  │
│ - computeStackFingerprint(stack): String                    │
│ - isDuplicateAnr(now, fingerprint): Boolean                 │
│ - captureMainThreadStack(): String                          │
└─────────────────────────────────────────────────────────────┘

┌──────────────────────────────────┐
│       AnrConfig (data class)     │
├──────────────────────────────────┤
│ checkIntervalMs: 5000            │
│ anrTimeoutMs: 5000               │
│ enableAnrMonitor: true           │
│ maxStackTraceLength: 4000        │
│ enableSigquitDetection: true     │
│ enableTracesFileReading: true    │
│ enableAnrClassification: true    │
│ anrDeduplicationWindowMs: 30000  │
│ anrSevereThresholdMs: 10000      │
│ stackSampleCount: 3              │
│ stackSampleIntervalMs: 100       │
└──────────────────────────────────┘
```

## ANR 双重检测流程

```
┌──────────────────────────────────────────────────────────────┐
│                    ANR 双重检测架构                            │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────── 检测通道 1 ───────────────────┐       │
│  │           SIGQUIT 信号检测                        │       │
│  │                                                  │       │
│  │  Android 系统在 ANR 发生时                       │       │
│  │  向进程发送 SIGQUIT (Signal 3)                    │       │
│  │       │                                          │       │
│  │       ▼                                          │       │
│  │  onSigquitReceived()                             │       │
│  │       │                                          │       │
│  │       ├── anrDetected.compareAndSet(false, true) │       │
│  │       │   └── 防止与 Watchdog 重复触发            │       │
│  │       │                                          │       │
│  │       └── handleAnrDetection("sigquit")          │       │
│  └──────────────────────────────────────────────────┘       │
│                                                              │
│  ┌─────────────────── 检测通道 2 ───────────────────┐       │
│  │           Watchdog 线程检测                       │       │
│  │                                                  │       │
│  │  watchdogThread 循环:                             │       │
│  │       │                                          │       │
│  │       ├── tick.set(false)                        │       │
│  │       ├── mainHandler.post { tick.set(true) }    │       │
│  │       ├── Thread.sleep(checkIntervalMs)          │       │
│  │       │   └── 默认 5000ms                        │       │
│  │       │                                          │       │
│  │       └── if (!tick.get())                       │       │
│  │           ├── 主线程无响应 → ANR                  │       │
│  │           ├── anrDetected.compareAndSet(false, true)│    │
│  │           └── handleAnrDetection("watchdog")     │       │
│  └──────────────────────────────────────────────────┘       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## ANR 分析处理流程

```
handleAnrDetection(source)
       │
       ├── AtomicBoolean CAS 防重入
       │
       ├── 1. 采集主线程堆栈
       │   └── captureMainThreadStack()
       │       └── Looper.getMainLooper().getThread().getStackTrace()
       │
       ├── 2. 堆栈采样 (配置的次数和间隔)
       │   └── collectStackSamples()
       │       ├── stackSampleCount 次 (默认 3)
       │       ├── 每次间隔 stackSampleIntervalMs (默认 100ms)
       │       └── 每次采集主线程堆栈
       │
       ├── 3. 原因分类
       │   └── classifyAnrCause(mainStack, samples)
       │       ├── CPU: 检测 CPU 密集操作（正则匹配）
       │       ├── IO: 检测 IO 操作（open/read/write/close）
       │       ├── LOCK: 检测锁竞争（wait/notify/ReentrantLock）
       │       ├── DEADLOCK: 检测死锁（BLOCKED 状态 + 多线程分析）
       │       ├── BINDER: 检测 Binder 调用（transact/onTransact）
       │       └── UNKNOWN: 未匹配已知模式
       │
       ├── 4. 读取 traces.txt
       │   └── readTracesFile()
       │       └── /data/anr/traces.txt（需系统权限）
       │
       ├── 5. 去重检查
       │   └── isDuplicateAnr(now, fingerprint)
       │       ├── computeStackFingerprint(stack)
       │       ├── 窗口内 (30s) + 指纹相同 → 跳过
       │       └── 否则更新 lastReportTime + lastStackFingerprint
       │
       └── 6. 上报事件
           └── Apm.emit(
                 module = "anr",
                 name = "anr_detected",
                 kind = ALERT,
                 severity = if (duration >= 10s) ERROR else WARN,
                 fields = {
                   source,           // "sigquit" 或 "watchdog"
                   mainThreadStack,
                   samples,          // 采样堆栈列表
                   cause,            // CPU/IO/LOCK/DEADLOCK/BINDER/UNKNOWN
                   tracesFile,       // traces.txt 内容
                   fingerprint,
                   durationMs
                 }
               )
```

## 去重机制

```
┌────────────────────────────────────────────────┐
│            ANR 去重窗口机制                      │
├────────────────────────────────────────────────┤
│                                                │
│  lastReportTimeMs: AtomicLong                  │
│  lastStackFingerprint: String @Volatile        │
│                                                │
│  新 ANR 发生:                                   │
│    fingerprint = computeStackFingerprint(stack)│
│    now = System.currentTimeMillis()            │
│                                                │
│    if (now - lastReportTime < 30s              │
│        && fingerprint == lastFingerprint)      │
│         → 跳过（重复 ANR）                      │
│    else                                        │
│         → 上报 + 更新窗口和指纹                 │
│                                                │
└────────────────────────────────────────────────┘
```
