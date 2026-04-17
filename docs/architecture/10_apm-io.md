# apm-io 模块架构

> IO 监控：Native PLT Hook + Java 代理 + FD 泄漏 + 吞吐量 + Closeable 泄漏

---

## 双层架构

```
┌──────────────────────────────────────────────────────────────┐
│                    IO 监控双层架构                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Level 2: Native PLT Hook (需 libapm-io.so)                 │
│  ┌──────────────────────────────────────────────┐           │
│  │  System.loadLibrary("apm-io")                │           │
│  │  nativeInstallIoHooks()                      │           │
│  │                                               │           │
│  │  拦截 libc 函数:                              │           │
│  │  ├── open()  → 记录 fd 和 path               │           │
│  │  ├── read()  → 记录读取字节数和耗时           │           │
│  │  ├── write() → 记录写入字节数和耗时           │           │
│  │  └── close() → 记录 fd 释放                  │           │
│  │                                               │           │
│  │  JNI 回调 → onNativeIoEvent()                │           │
│  └──────────────────────────────────────────────┘           │
│                     │ 降级                                   │
│                     ▼                                       │
│  Level 1: Java 层代理 (默认, 零依赖)                         │
│  ┌──────────────────────────────────────────────┐           │
│  │  wrapInputStream(source, path)                │           │
│  │  wrapOutputStream(source, path)               │           │
│  │  onRead(path, bytesRead, bufferUsed)          │           │
│  │  onClose(source, totalBytes)                  │           │
│  └──────────────────────────────────────────────┘           │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## 类图

```
┌──────────────────────────────────────────────────────────┐
│                   NativeIoHook                             │
├──────────────────────────────────────────────────────────┤
│ config: IoConfig                                          │
│                                                           │
│ IO 会话:                                                   │
│ activeSessions: ConcurrentHashMap<Int, IoSession>        │
│ readFileCounts: ConcurrentHashMap<String, Int>            │
│                                                           │
│ Closeable 泄漏:                                           │
│ closeableRefs: ConcurrentHashMap<PhantomRef, String>      │
│ closeableQueue: ReferenceQueue<Any>                       │
│                                                           │
│ FD 泄漏:                                                   │
│ openFdPaths: ConcurrentHashMap<Int, String>               │
│ fdAllocCount / fdReleaseCount: AtomicLong                 │
│                                                           │
│ 吞吐量:                                                   │
│ totalReadBytes / totalWriteBytes: AtomicLong              │
│ totalIoOps: AtomicLong                                    │
│ pathThroughput: ConcurrentHashMap<String, ThroughputStats>│
│                                                           │
│ Native Hook 状态:                                         │
│ nativeHookInstalled: Boolean @Volatile                    │
│ initialized: Boolean @Volatile                            │
├──────────────────────────────────────────────────────────┤
│ + init()                                                  │
│ + wrapInputStream(source, path): InputStream              │
│ + wrapOutputStream(source, path): OutputStream            │
│ + onRead(path, bytesRead, bufferUsed)                     │
│ + onClose(source, totalBytes)                             │
│ + getThroughputStats(): List<ThroughputStats>             │
│ + getGlobalStats(): Map<String, Long>                     │
│ + destroy()                                               │
│                                                           │
│ - installNativePltHook()                                  │
│ - onNativeIoEvent(op, path, bytes, durationMs, isMain)   │
│ - monitorCloseableLeaks()                                 │
│ - monitorFdLeaks()                                        │
│ - countOpenFds(): Int                                     │
│ - recordFdOpen/Close(path)                                │
│ - updatePathThroughput(path, bytes, isWrite)              │
├──────────────────────────────────────────────────────────┤
│ Native 方法:                                               │
│ - external nativeInstallIoHooks()                         │
│ - external nativeUninstallIoHooks()                       │
├──────────────────────────────────────────────────────────┤
│ «data» IoSession(path, openTime, threadName, isMainThread)│
│ «class» ThroughputStats(path, readBytes, writeBytes, ops) │
└──────────────────────────────────────────────────────────┘
```

## IO Hook 初始化流程

```
init()
       │
       ├── if (enableNativePltHook)
       │   └── installNativePltHook()
       │       ├── System.loadLibrary("apm-io")
       │       ├── nativeInstallIoHooks()
       │       └── nativeHookInstalled = true
       │       ├── catch UnsatisfiedLinkError → 降级
       │       └── catch Exception → 降级
       │
       ├── if (enableCloseableLeak)
       │   └── 启动 "apm-io-leak-monitor" 守护线程
       │       └── monitorCloseableLeaks()
       │
       └── if (enableFdLeakDetection)
           └── 启动 "apm-io-fd-monitor" 守护线程
               └── monitorFdLeaks()
```

## IO 检测维度

```
┌────────────────────────────────────────────────────────────┐
│                    IO 检测 8 维度                            │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  1. 主线程 IO 耗时                                         │
│     onClose() → session.isMainThread && duration >= 50ms   │
│     → emit("io_main_thread", ERROR)                        │
│                                                            │
│  2. 小 Buffer 检测                                         │
│     onRead() → bufferUsed < 4096                           │
│     → emit("io_small_buffer", INFO)                        │
│                                                            │
│  3. 重复读检测                                              │
│     onRead() → readFileCounts[path] >= 5                   │
│     → emit("io_duplicate_read", WARN)                      │
│                                                            │
│  4. Closeable 泄漏                                         │
│     PhantomReference 入 ReferenceQueue (GC 回收但未 close) │
│     → emit("io_closeable_leak", WARN)                      │
│                                                            │
│  5. FD 泄漏检测                                            │
│     /proc/self/fd 文件数 >= 500                            │
│     → emit("io_fd_leak", ERROR)                            │
│                                                            │
│  6. 吞吐量统计                                             │
│     全局: totalRead/WriteBytes, totalIoOps                 │
│     路径: pathThroughput (read/write/opCount per path)     │
│                                                            │
│  7. Native PLT Hook (Level 2)                              │
│     拦截 libc open/read/write/close                        │
│     JNI 回调 onNativeIoEvent()                             │
│                                                            │
│  8. 零拷贝检测 (预留)                                      │
│     config.enableZeroCopyDetection (当前 false)            │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

## Closeable 泄漏检测流程

```
┌────────────────────────────────────────────────────────┐
│          Closeable 泄漏检测 (PhantomReference)          │
├────────────────────────────────────────────────────────┤
│                                                        │
│  wrapInputStream/OutputStream:                         │
│       └── closeableRefs[                               │
│             PhantomReference(stream, closeableQueue)   │
│           ] = path                                     │
│                                                        │
│  泄漏监控线程 (apm-io-leak-monitor):                    │
│       │                                                │
│       └── while (initialized)                          │
│           │                                            │
│           ├── ref = closeableQueue.remove(1000ms)      │
│           │   └── 阻塞等待，GC 回收到队列               │
│           │                                            │
│           └── if (ref != null)                         │
│               ├── path = closeableRefs.remove(ref)     │
│               └── if (path != null)                    │
│                   └── Apm.emit(                        │
│                         "io_closeable_leak",            │
│                         WARN,                           │
│                         { path }                        │
│                       )                                 │
│                                                        │
│  说明:                                                  │
│  PhantomReference 被 GC 回收 → 入 ReferenceQueue       │
│  意味着流对象已被 GC 但未被显式 close → 泄漏            │
│                                                        │
└────────────────────────────────────────────────────────┘
```

## FD 泄漏检测流程

```
┌────────────────────────────────────────────────────────┐
│            FD 泄漏检测 (/proc/self/fd)                  │
├────────────────────────────────────────────────────────┤
│                                                        │
│  FD 监控线程 (apm-io-fd-monitor):                       │
│       │                                                │
│       └── while (initialized)                          │
│           │                                            │
│           ├── sleep(5000ms)                            │
│           │                                            │
│           ├── fdCount = countOpenFds()                 │
│           │   └── File("/proc/self/fd").listFiles()    │
│           │       .size                                │
│           │                                            │
│           └── if (fdCount >= fdLeakThreshold)          │
│               │   └── 默认 500                         │
│               ├── leakedPaths = openFdPaths.values     │
│               │   .take(10)                            │
│               └── Apm.emit(                            │
│                     "io_fd_leak",                       │
│                     ERROR,                              │
│                     {                                   │
│                       fdCount,                          │
│                       threshold,                        │
│                       fdAllocCount,                     │
│                       fdReleaseCount,                   │
│                       leakedPaths                       │
│                     }                                   │
│                   )                                     │
│                                                        │
└────────────────────────────────────────────────────────┘
```
