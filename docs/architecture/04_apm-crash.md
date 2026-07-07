# apm-crash 模块架构

> 崩溃监控：Java 崩溃 + Native 崩溃 + Tombstone 解析

## 2026-07-07 优化更新

- `logNativeCrashSignal` 补 `@JvmStatic`：JNI 以 GetStaticMethodID 查找，此前查找必然失败导致 JNI_OnLoad 失败、native 采集静默降级为 tombstone-only；契约测试锁定。
- 新增 `ExitReasonCollector`（API 30+）：启动后台线程读取 `ApplicationExitInfo` 历史退出原因（ANR/native crash/LMK OOM/系统信号等），SharedPreferences 时间戳去重，ANR 附系统 trace（默认截断 64KB），以 `app_exit` 事件上报；`CrashConfig.collectExitInfo` 默认开启。
- `ExitInfoSource`/`ExitTimestampStore` seam 使采集逻辑可在 JVM 单测覆盖。

## 2026-07-04 实现状态

- Java 崩溃事件通过 `Apm.emitCriticalSync()` 在调用原始异常处理器前同步写入本地持久化 outbox。
- 同步路径不执行阻塞网络请求；上传进程直接落盘，非上传进程同步发布 IPC 文件，上传由持久化 worker 在当前或下次进程启动时完成。
- `libapm-crash.so` CMake 目标添加 16KB page-size linker alignment，满足 Android 16KB 页面兼容检查。

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
│   ├── Thread.setDefaultUncaughtExceptionHandler│
│   ├── NativeCrashMonitor.checkRecentTombstone()│
│   └── NativeCrashMonitor.init(unsafeCallback) │
│ + onStop()                                   │
│ + stackTraceToString(throwable): String      │
└──────────────────────────────────────────────┘

┌──────────────────────┐  ┌──────────────────────────────┐
│ CrashConfig           │  │ NativeCrashMonitor «object»   │
│ (data class)          │  ├──────────────────────────────┤
├──────────────────────┤  │ - initialized: Boolean        │
│ enableJavaCrash: Bool │  │ - lastCheckTime: Long         │
│ enableNativeCrash:Bool│  ├──────────────────────────────┤
│ unsafeCallback: Bool  │  │ + init(unsafeSignalCallback)  │
│ maxStackTraceLength   │  │ + destroy()                  │
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
│  方式 1: 安全信号重抛 (libapm-crash.so，默认)  │
│  ┌──────────────────────────────┐               │
│  │ 信号处理 (SIGSEGV/SIGABRT/..)│               │
│  │ → 恢复原始 handler           │               │
│  │ → raise(sig)                 │               │
│  │ → 系统生成 tombstone         │               │
│  └──────────────────────────────┘               │
│                                                 │
│  方式 2: Tombstone 扫描 (默认上报路径)          │
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
│  方式 3: unsafe JNI 回调 (仅调试显式开启)        │
│  ┌──────────────────────────────┐               │
│  │ 信号处理器内采集线程/栈/地址  │               │
│  │ → logNativeCrashSignal()     │               │
│  │ → Apm.emit(ALERT, FATAL)     │               │
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
