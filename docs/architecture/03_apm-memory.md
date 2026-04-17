# apm-memory 模块架构

> 内存监控：采样、泄漏检测、OOM 预警、Hprof、Native Heap

---

## 模块架构图

```
┌───────────────────────────────────────────────────────────────┐
│                       MemoryModule                            │
│                    (ApmModule 实现)                            │
├───────────────────────────────────────────────────────────────┤
│ config: MemoryConfig                                          │
│ sampler: MemorySampler                                        │
│ reporter: MemoryReporter                                      │
│ scheduler: MemorySampleScheduler                              │
│ fragmentLeakDetectors: ConcurrentHashMap<Activity, Fragment..>│
│ activityLeakDetector: ActivityLeakDetector                    │
│ oomMonitor: OomMonitor                                        │
│ nativeHeapMonitor: NativeHeapMonitor                          │
├───────────────────────────────────────────────────────────────┤
│ onInitialize() → 创建各组件                                   │
│ onStart()      → 注册生命周期 + 启动调度器 + 启动子模块       │
│ onStop()       → 停止调度 + 注销回调 + 释放资源               │
│ captureOnce(reason) → 单次采集                                │
└───────┬──────────────┬──────────────┬──────────────┬─────────┘
        │              │              │              │
        ▼              ▼              ▼              ▼
┌──────────────┐ ┌───────────┐ ┌──────────┐ ┌───────────────┐
│MemorySampler │ │ Leak      │ │ OOM      │ │ Native Heap   │
│              │ │ Detection │ │ Monitor  │ │ Monitor       │
│ Heap/PSS     │ │           │ │          │ │               │
│ ProcStatus   │ │ Activity  │ │ JavaHeap │ │ Alloc Stats   │
│ GC Stats     │ │ Fragment  │ │ SysMem   │ │ Peak Tracking │
│              │ │ ViewModel │ │ Native   │ │               │
└──────┬───────┘ └─────┬─────┘ └────┬─────┘ └──────┬────────┘
       │               │            │               │
       ▼               ▼            ▼               ▼
┌─────────────────────────────────────────────────────────────┐
│                    MemoryReporter                            │
│  onSnapshot() → METRIC/ALERT 事件上报                       │
│  onLeakFound() → ALERT 事件上报                             │
│                    ↓ Apm.emit()                              │
└─────────────────────────────────────────────────────────────┘
```

## 类图

```
┌───────────────────┐     ┌────────────────────┐
│ MemoryConfig      │     │ MemorySnapshot     │
│ (data class)      │     │ (data class)        │
├───────────────────┤     ├────────────────────┤
│ foregroundInterval│     │ javaHeapUsedMb     │
│ backgroundInterval│     │ javaHeapMaxMb      │
│ javaHeapWarnRatio │     │ totalPssKb         │
│ javaHeapCritical  │     │ dalvikPssKb        │
│ totalPssWarnKb    │     │ nativePssKb        │
│ sampleRate        │     │ nativeHeapAllocKb  │
│ enableActivityLeak│     │ systemAvailMemKb   │
│ enableFragmentLeak│     │ vmRssKb            │
│ enableOomMonitor  │     │ gcCount / gcTimeMs │
│ enableHprofDump   │     │ processName        │
│ enableNativeMonitr│     │ scene / foreground │
└───────────────────┘     ├────────────────────┤
                          │ toFields(reason)   │
                          └────────────────────┘

┌───────────────────┐     ┌────────────────────┐
│ MemorySampler     │     │MemorySampleScheduler│
├───────────────────┤     ├────────────────────┤
│- activityManager  │     │- executor: Scheduled│
│- runtime: Runtime │     │- future: Scheduled? │
├───────────────────┤     ├────────────────────┤
│+buildSnapshot()   │     │+ start(interval)   │
│+collectGcStats()  │     │+ reschedule(intv)  │
│+collectProcStatus│     │+ stop()            │
│  /proc/self/status│     └────────────────────┘
└───────────────────┘

┌───────────────────┐     ┌────────────────────┐
│ MemoryReporter    │     │ LeakResult          │
├───────────────────┤     │ (data class)        │
│- config: MemoryConf│    ├────────────────────┤
├───────────────────┤     │ leakClass: String   │
│+onSnapshot(snap)  │     │ type: LeakType      │
│+onLeakFound(result│     │ retainedCount: Int  │
│                   │     │ suspectFields: List │
│ emit METRIC/ALERT │     │ referenceChain: List│
└───────────────────┘     └────────────────────┘
```

## 泄漏检测架构

```
┌─────────────────────────────────────────────────────────┐
│                 Activity 泄漏检测流程                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Activity.onDestroy()                                   │
│       │                                                 │
│       ▼                                                 │
│  ActivityLeakDetector.onActivityDestroyed(activity)     │
│       │                                                 │
│       ├── key = className + "@" + identityHashCode      │
│       ├── watchedActivities[key] = WeakReference(act)   │
│       └── 延迟 5 秒后检查                               │
│            │                                            │
│            ▼                                            │
│  triggerGcAndCheck(key, className)                      │
│       │                                                 │
│       ├── checkThread HandlerThread (非主线程)           │
│       ├── System.gc() + Sleep(100ms)                    │
│       └── 检查 WeakReference.get()                      │
│            │                                            │
│            ├── get() == null → 已回收，无泄漏            │
│            └── get() != null → 泄漏！                   │
│                 │                                       │
│                 ├── analyzeReferenceChain(activity)      │
│                 │   └── 反射分析 GC Root 引用链          │
│                 │                                       │
│                 └── reporter.onLeakFound(result)         │
│                      └── Apm.emit(ALERT)                │
│                                                         │
├─────────────────────────────────────────────────────────┤
│                 Fragment 泄漏检测流程                     │
├─────────────────────────────────────────────────────────┤
│  FragmentManager.registerFragmentLifecycleCallbacks     │
│       │                                                 │
│       ▼                                                 │
│  onFragmentViewDestroyed(fm, fragment)                  │
│       │                                                 │
│       ├── key = fragmentClass + "@" + hashCode          │
│       ├── watchedFragments[key] = WeakReference(frag)   │
│       └── 延迟检查 (主线程 Handler)                      │
│            │                                            │
│            ▼                                            │
│  checkFragment(key, className)                          │
│       └── WeakRef.get() != null → 泄漏 → 上报          │
├─────────────────────────────────────────────────────────┤
│                 ViewModel 泄漏检测                        │
├─────────────────────────────────────────────────────────┤
│  ViewModelLeakDetector.checkViewModel(viewModel)        │
│       │                                                 │
│       ├── 反射遍历 ViewModel 所有字段                    │
│       ├── 检测 Context / View 类型引用                   │
│       └── 发现泄漏字段 → LeakResult → 上报              │
└─────────────────────────────────────────────────────────┘
```

## OOM 监控流程

```
每次采集 MemorySnapshot 后
       │
       ▼
OomMonitor.check(snapshot)
       │
       ├── checkJavaHeapThreshold(snapshot)
       │   └── javaHeapUsed / javaHeapMax > criticalRatio (0.90)
       │       → 触发 Hprof Dump
       │
       ├── checkSystemLowMemory(snapshot)
       │   └── systemAvailMem < lowMemThreshold × 3 / 2
       │       → 触发 Hprof Dump
       │
       └── checkNativeHeapThreshold(snapshot)
           └── nativeHeapAllocated > threshold
               → 触发 Hprof Dump
                    │
                    ▼
              triggerDump(reason)
                    │
                    ├── 冷却检查（CAS 防重入）
                    │   AtomicLong lastDumpTime
                    │   AtomicBoolean hasTriggeredDump
                    │
                    └── HprofDumper.dumpAsync(reason)
                         │
                         ├── dumpExecutor.submit {
                         │     Debug.dumpHprofData(path)
                         │ }
                         │
                         └── dump 完成后
                              └── HprofStripProcessor.strip()
                                   └── 清零 primitive array，保留引用链
                                      压缩率 60~80%
```

## 采样调度流程

```
┌───────────────────────────────────────────────────┐
│  ProcessLifecycleOwner                             │
│                                                   │
│  ON_START (前台)                                   │
│    └── scheduler.reschedule(foregroundInterval)   │
│        └── 15 秒间隔采集                           │
│                                                   │
│  ON_STOP (后台)                                    │
│    └── scheduler.reschedule(backgroundInterval)   │
│        └── 60 秒间隔采集                           │
│                                                   │
│  每次 tick:                                       │
│    └── captureOnce("periodic")                    │
│         ├── sampler.buildSnapshot()               │
│         ├── reporter.onSnapshot(snapshot)         │
│         │    ├── 阈值检查 (Heap/PSS)               │
│         │    └── Apm.emit(METRIC/ALERT)            │
│         └── oomMonitor.check(snapshot)            │
└───────────────────────────────────────────────────┘
```
