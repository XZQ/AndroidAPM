package com.apm.gcmonitor

/**
 * GC 监控模块配置。
 * 包含基础 GC 统计、对象分配速率、GC 回收分析配置。
 */
data class GcMonitorConfig(
    /** 是否开启 GC 监控。 */
    val enableGcMonitor: Boolean = true,
    /** 检测间隔（毫秒）。 */
    val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    /** GC 次数增长告警阈值。 */
    val gcCountSpikeThreshold: Int = DEFAULT_GC_COUNT_SPIKE_THRESHOLD,
    /** GC 耗时占比告警阈值（0.0~1.0）。 */
    val gcTimeRatioThreshold: Float = DEFAULT_GC_TIME_RATIO_THRESHOLD,
    /** Heap 使用率飙升阈值（0.0~1.0）。 */
    val heapGrowthThreshold: Float = DEFAULT_HEAP_GROWTH_THRESHOLD,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用对象分配速率检测。 */
    val enableAllocationRate: Boolean = true,
    /** 分配速率告警阈值（KB/秒）。 */
    val allocationRateThresholdKbPerSec: Float = DEFAULT_ALLOCATION_RATE_THRESHOLD,
    /** 是否启用 GC 回收率分析。 */
    val enableGcReclaimAnalysis: Boolean = true,
    /** GC 回收率低于此值告警（0-1）。 */
    val gcLowReclaimRate: Float = DEFAULT_GC_LOW_RECLAIM_RATE
) {
    companion object {
        /** 默认检测间隔：10 秒。 */
        private const val DEFAULT_CHECK_INTERVAL_MS = 10_000L
        /** 默认 GC 次数飙升阈值：5 次/窗口。 */
        private const val DEFAULT_GC_COUNT_SPIKE_THRESHOLD = 5
        /** 默认 GC 耗时占比阈值：10%。 */
        private const val DEFAULT_GC_TIME_RATIO_THRESHOLD = 0.10f
        /** 默认 Heap 增长阈值：20%。 */
        private const val DEFAULT_HEAP_GROWTH_THRESHOLD = 0.20f
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认分配速率阈值：1MB/s。 */
        private const val DEFAULT_ALLOCATION_RATE_THRESHOLD = 1024f
        /** 默认 GC 低回收率阈值：10%。 */
        private const val DEFAULT_GC_LOW_RECLAIM_RATE = 0.10f
    }
}
