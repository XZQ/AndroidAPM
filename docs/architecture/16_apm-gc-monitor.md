# apm-gc-monitor 模块架构

> GC 监控：GC 频次飙升 / GC 耗时占比 / Heap 增长 / 分配频率 / GC 回收率

---

## 类图

```
┌──────────────────────────────────────────────────┐
│          GcMonitorModule                          │
│       (implements ApmModule)                       │
├──────────────────────────────────────────────────┤
│ - config: GcMonitorConfig                       │
│ - apmContext: ApmContext?                         │
│ - mainHandler: Handler                           │
│ - monitoring: Boolean @Volatile                  │
│ - lastStats: GcStats? (上次快照)                 │
│ - checkTask: Runnable (定时 10s)                  │
├──────────────────────────────────────────────────┤
│ + onInitialize(context)                          │
│ + onStart() / onStop()                           │
│ + checkGc()                                      │
│ + collectGcStats(): GcStats?                     │
│ + getRuntimeStat(statName): String?              │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│  GcMonitorConfig (data class)             │
├──────────────────────────────────────────┤
│ enableGcMonitor: true                     │
│ checkIntervalMs: 10_000 (10s)             │
│ gcCountSpikeThreshold: 5                  │
│ gcTimeRatioThreshold: 0.10 (10%)          │
│ heapGrowthThreshold: 0.20 (20%)           │
│ maxStackTraceLength: 4000                 │
│ enableAllocationRate: true                 │
│ allocationRateThresholdKbPerSec: 1024.0   │
│ enableGcReclaimAnalysis: true              │
│ gcLowReclaimRate: 0.10 (10%)              │
└──────────────────────────────────────────┘

┌──────────────────────────────────┐
│  GcStats (data class)            │
├──────────────────────────────────┤
│ gcCount: Long                    │
│ gcTimeMs: Long                   │
│ javaHeapUsed: Long               │
│ javaHeapMax: Long                │
│ timestamp: Long                  │
│ allocationRateKbPerSec: Float    │
│ gcReclaimBytes: Long             │
│ gcReclaimRate: Float             │
│ bytesAllocated: Long             │
│ bytesFreed: Long                 │
└──────────────────────────────────┘
```

## GC 数据采集

```
collectGcStats()
       │
       ├── 反射调用 Debug.getRuntimeStat(statName)
       │   ├── "art.gc.gc-count"        → gcCount
       │   ├── "art.gc.gc-time"         → gcTimeMs
       │   ├── "art.gc.bytes-allocated" → bytesAllocated
       │   └── "art.gc.bytes-freed"     → bytesFreed
       │
       ├── javaHeapUsed = Runtime.totalMemory() - Runtime.freeMemory()
       ├── javaHeapMax = Runtime.maxMemory()
       │
       └── 计算派生指标:
           ├── allocationRate = (allocBytes - lastAllocBytes) / interval
           ├── gcReclaimRate = freedBytes / allocBytes
           └── gcTimeRatio = (gcTime - lastGcTime) / intervalMs
```

## 检测流程

```
checkGc() (每 10s 执行一次)
       │
       ├── currentStats = collectGcStats()
       │
       ├── if (lastStats == null) → 保存并返回
       │
       ├── 1. GC 次数飙升检测
       │   └── (gcCount - lastGcCount) >= gcCountSpikeThreshold (5次/10s)
       │
       ├── 2. GC 耗时占比检测
       │   └── gcTimeRatio = (gcTimeMs - lastGcTimeMs) / intervalMs
       │       >= gcTimeRatioThreshold (10%)
       │
       ├── 3. Heap 增长检测
       │   └── heapGrowth = (heapUsed - lastHeapUsed) / lastHeapUsed
       │       >= heapGrowthThreshold (20%)
       │
       ├── 4. 分配频率检测
       │   └── allocationRateKbPerSec >= allocationRateThreshold (1MB/s)
       │
       ├── 5. GC 回收率低检测
       │   └── gcReclaimRate <= gcLowReclaimRate (10%)
       │       (分配多但回收少 → 内存压力大)
       │
       └── 任何条件满足:
           → emit("memory_churn", WARN/ERROR)
           fields: {
             gcCountDelta, gcTimeRatio,
             heapGrowthPercent, allocationRate,
             reclaimRate, heapUsed, heapMax
           }

       lastStats = currentStats
```
