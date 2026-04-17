# apm-crash 模块架构

> 崩溃监控：Java 崩溃 + Native 崩溃 + Tombstone 解析

---

## 类图

```
┌──────────────────────────────────────────────┐
│             CrashModule                       │
│         (implements ApmModule)                │
├──────────────────────────────────────────────┤
│ - previousHandler: UncaughtExceptionHandler? │
│ - apmContext: ApmContext?                     │
│ - config: CrashConfig                        │
├──────────────────────────────────────────────┤
│ + onInitialize(context)                      │
│ + onStart()                                  │
│   └── Thread.setDefaultUncaughtExceptionHandler│
│ + onStop()                                   │
│ + stackTraceToString(throwable): String      │
└──────────────────────────────────────────────┘

┌──────────────────────┐  ┌──────────────────────────────┐
│ CrashConfig           │  │ NativeCrashMonitor «object»   │
│ (data class)          │  ├──────────────────────────────┤
├──────────────────────┤  │ - initialized: Boolean        │
│ enableJavaCrash: Bool │  │ - lastCheckTime: Long         │
│ enableNativeCrash:Bool│  ├──────────────────────────────┤
│ maxStackTraceLength   │  │ + init()                     │
└──────────────────────┘  │ + logNativeCrashSignal(...)   │
                          │ + checkRecentTombstone()      │
                          │ + parseAndReportTombstone()   │
                          │ + signalName(signal): String  │
                          └──────────────────────────────┘
```

## Java 崩溃检测流程

```
┌─────────────────────────────────────────────────┐
│              Java Crash 检测流程                  │
├─────────────────────────────────────────────────┤
│                                                 │
│  未捕获异常发生                                   │
│       │                                         │
│       ▼                                         │
│  CrashModule.uncaughtException(t, e)            │
│       │                                         │
│       ├── stackTrace = stackTraceToString(e)    │
│       │   └── 递归遍历 cause 链                  │
│       │                                         │
│       ├── Apm.emit(                             │
│       │     module = "crash",                   │
│       │     name = "java_crash",                │
│       │     kind = ALERT,                       │
│       │     severity = FATAL,                   │
│       │     fields = {                          │
│       │       exceptionClass,                   │
│       │       exceptionMessage,                 │
│       │       stackTrace,                       │
│       │       threadName                        │
│       │     }                                   │
│       │   )                                     │
│       │                                         │
│       └── previousHandler?.uncaughtException()  │
│           └── 传递给原有 Handler（非破坏性）     │
│                                                 │
└─────────────────────────────────────────────────┘
```

## Native 崩溃检测流程

```
┌─────────────────────────────────────────────────┐
│             Native Crash 检测流程                 │
├─────────────────────────────────────────────────┤
│                                                 │
│  方式 1: JNI 回调 (libapm-crash.so)             │
│  ┌──────────────────────────────┐               │
│  │ 信号处理 (SIGSEGV/SIGABRT/..)│               │
│  │ → logNativeCrashSignal()     │               │
│  │   → Apm.emit(ALERT, FATAL)   │               │
│  └──────────────────────────────┘               │
│                                                 │
│  方式 2: Tombstone 扫描 (降级方案)               │
│  ┌──────────────────────────────┐               │
│  │ checkRecentTombstone()       │               │
│  │ ├── 读取 /data/tombstones/   │               │
│  │ ├── 检查文件修改时间          │               │
│  │ ├── 解析 Tombstone 格式       │               │
│  │ │   ├── Signal 类型           │               │
│  │ │   ├── 故障地址              │               │
│  │ │   ├── Backtrace 堆栈        │               │
│  │ │   └── 线程信息              │               │
│  │ └── parseAndReportTombstone() │               │
│  │     → Apm.emit(ALERT, FATAL)  │               │
│  └──────────────────────────────┘               │
│                                                 │
│  信号名称映射:                                   │
│  SIGSEGV → "Segmentation Fault"                 │
│  SIGABRT → "Abort"                              │
│  SIGFPE  → "Floating Point Exception"           │
│  SIGBUS  → "Bus Error"                          │
│  SIGTRAP → "Trace/Breakpoint Trap"              │
│                                                 │
└─────────────────────────────────────────────────┘
```
