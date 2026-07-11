package com.apm.gcmonitor

import android.os.SystemClock
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmExecutors
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * GC 监控模块（Memory Churn 检测）。
 * 定期采集 GC 次数和耗时，检测高频 GC 导致的卡顿。
 *
 * 监控策略：
 * 1. 定期读取 Debug.getRuntimeStat 获取 GC 统计
 * 2. 对比前后两次快照，计算窗口内 GC 增量
 * 3. GC 次数飙升 / GC 耗时占比过高 / Heap 快速增长 时告警
 *
 * 原理：频繁的 GC（Memory Churn）会导致主线程卡顿，
 * 大量短命对象分配是常见原因。本模块帮助定位此类问题。
 */
class GcMonitorModule(private val config: GcMonitorConfig = GcMonitorConfig()) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** Serial background sampler created for each started lifecycle. */
    private var sampler: ScheduledExecutorService? = null
    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false
    /** 上一次 GC 统计快照。 */
    private var lastStats: GcStats? = null

    /** 定时检测任务。 */
    private val checkTask = Runnable {
        if (!monitoring) {
            return@Runnable
        }
        try {
            checkGc()
        } catch (error: RuntimeException) {
            // Scheduled executors suppress future runs after an uncaught exception.
            Apm.recordInternalError(ERROR_TAG_SAMPLE_LOOP, error)
        }
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        if (!config.enableGcMonitor || monitoring) {
            return
        }
        monitoring = true
        lastStats = null
        sampler = ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME_SAMPLER).also { executor ->
            executor.scheduleWithFixedDelay(
                checkTask,
                INITIAL_DELAY_MS,
                config.checkIntervalMs.coerceAtLeast(MIN_CHECK_INTERVAL_MS),
                TimeUnit.MILLISECONDS
            )
        }
        apmContext?.logger?.d("GC monitor started")
    }

    override fun onStop() {
        monitoring = false
        sampler?.shutdownNow()
        sampler = null
        lastStats = null
    }

    /**
     * 执行 GC 检测。
     * 采集当前 GC 统计，与上次快照对比。
     */
    private fun checkGc() {
        val current = collectGcStats() ?: return
        val prev = lastStats ?: run {
            lastStats = current
            return
        }

        val windowMs = current.timestamp - prev.timestamp
        val windowMetrics = GcWindowMetrics.calculate(prev, current)
        val gcCountDelta = windowMetrics.gcCountDelta
        val gcTimeDelta = windowMetrics.gcTimeDeltaMs

        // 计算 Heap 增长
        val prevHeapRatio = if (prev.javaHeapMax > 0) {
            prev.javaHeapUsed.toFloat() / prev.javaHeapMax
        } else 0f
        val currHeapRatio = if (current.javaHeapMax > 0) {
            current.javaHeapUsed.toFloat() / current.javaHeapMax
        } else 0f
        val heapGrowth = currHeapRatio - prevHeapRatio

        // GC 耗时占比
        val gcTimeRatio = if (windowMs > 0) {
            gcTimeDelta.toFloat() / windowMs
        } else 0f

        // 判断是否需要告警
        var needReport = false
        var reason = ""

        // GC 次数飙升
        if (windowMetrics.gcCountersAvailable && gcCountDelta >= config.gcCountSpikeThreshold) {
            needReport = true
            reason = "GC count spike: $gcCountDelta in ${windowMs}ms"
        }

        // GC 耗时占比过高
        if (windowMetrics.gcCountersAvailable && gcTimeRatio >= config.gcTimeRatioThreshold) {
            needReport = true
            if (reason.isNotEmpty()) reason += "; "
            reason += "GC time ratio: ${"%.1f%%".format(gcTimeRatio * 100)}"
        }

        // Heap 快速增长（Memory Churn 典型特征）
        if (heapGrowth >= config.heapGrowthThreshold) {
            needReport = true
            if (reason.isNotEmpty()) reason += "; "
            reason += "Heap growth: ${"%.1f%%".format(heapGrowth * 100)}"
        }

        // ART cumulative allocation counters make this calculation independent
        // of heap growth, which can be hidden by GC within the same window.
        if (config.enableAllocationRate && windowMetrics.allocationRateAvailable &&
            windowMetrics.allocationRateKbPerSec >= config.allocationRateThresholdKbPerSec
        ) {
            needReport = true
            if (reason.isNotEmpty()) reason += "; "
            reason += "Allocation rate: ${"%.1f".format(windowMetrics.allocationRateKbPerSec)} KiB/s"
        }

        // A reclaim ratio is meaningful only when both a GC and allocation
        // occurred in this sampling window.
        if (config.enableGcReclaimAnalysis && windowMetrics.reclaimRateAvailable &&
            gcCountDelta > 0L && windowMetrics.allocatedBytes > 0L &&
            windowMetrics.gcReclaimRate <= config.gcLowReclaimRate
        ) {
            needReport = true
            if (reason.isNotEmpty()) reason += "; "
            reason += "Low GC reclaim rate: ${"%.1f%%".format(windowMetrics.gcReclaimRate * 100)}"
        }

        if (needReport) {
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_MEMORY_CHURN,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
                fields = mapOf(
                    FIELD_GC_COUNT_DELTA to gcCountDelta,
                    FIELD_GC_TIME_DELTA_MS to gcTimeDelta,
                    FIELD_GC_COUNTERS_AVAILABLE to windowMetrics.gcCountersAvailable,
                    FIELD_GC_TIME_RATIO to gcTimeRatio,
                    FIELD_HEAP_GROWTH to heapGrowth,
                    FIELD_HEAP_USED_RATIO to currHeapRatio,
                    FIELD_ALLOCATION_RATE_KIB_PER_SEC to windowMetrics.allocationRateKbPerSec,
                    FIELD_GC_RECLAIM_BYTES to windowMetrics.gcReclaimBytes,
                    FIELD_GC_RECLAIM_RATE to windowMetrics.gcReclaimRate,
                    FIELD_ALLOCATION_RATE_AVAILABLE to windowMetrics.allocationRateAvailable,
                    FIELD_RECLAIM_RATE_AVAILABLE to windowMetrics.reclaimRateAvailable,
                    FIELD_WINDOW_MS to windowMs,
                    FIELD_REASON to reason
                )
            )
        }

        // Retain the derived values with the raw cumulative counters so a
        // debugger inspecting the latest snapshot sees the same reported window.
        lastStats = current.copy(
            allocationRateKbPerSec = windowMetrics.allocationRateKbPerSec,
            gcReclaimBytes = windowMetrics.gcReclaimBytes,
            gcReclaimRate = windowMetrics.gcReclaimRate
        )
    }

    /**
     * 采集当前 GC 统计。
     * 通过 Debug.getRuntimeStat 读取 GC 次数和耗时。
     */
    private fun collectGcStats(): GcStats? {
        return try {
            val runtime = Runtime.getRuntime()
            val gcCount = getRuntimeStat(STAT_GC_COUNT)?.toLongOrNull() ?: COUNTER_UNAVAILABLE
            val gcTimeMs = getRuntimeStat(STAT_GC_TIME)?.toLongOrNull() ?: COUNTER_UNAVAILABLE
            val bytesAllocated = getRuntimeStat(STAT_BYTES_ALLOCATED)?.toLongOrNull() ?: COUNTER_UNAVAILABLE
            val bytesFreed = getRuntimeStat(STAT_BYTES_FREED)?.toLongOrNull() ?: COUNTER_UNAVAILABLE
            // gc-time 单位是 ms（API 23+）
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            val heapMax = runtime.maxMemory()

            GcStats(
                gcCount = gcCount,
                gcTimeMs = gcTimeMs,
                javaHeapUsed = heapUsed,
                javaHeapMax = heapMax,
                timestamp = SystemClock.elapsedRealtime(),
                bytesAllocated = bytesAllocated,
                bytesFreed = bytesFreed
            )
        } catch (e: Exception) {
            Apm.recordInternalError(ERROR_TAG_COLLECT_STATS, e)
            null
        }
    }

    /**
     * 读取 Debug.getRuntimeStat。
     * Debug.getRuntimeStat 自 API 23 起为公开 API，minSdk 24 下可直接调用，
     * 无需反射（此前的反射写法是不必要的间接层）。
     */
    private fun getRuntimeStat(statName: String): String? {
        return try {
            // ART 未提供该统计项时返回 null，由调用方降级为 0
            android.os.Debug.getRuntimeStat(statName)
        } catch (e: Exception) {
            // 个别 ROM 实现异常时降级，并记入自监控
            Apm.recordInternalError(ERROR_TAG_RUNTIME_STAT, e)
            null
        }
    }

    companion object {
        /** 自监控 tag：Debug.getRuntimeStat 调用失败。 */
        private const val ERROR_TAG_RUNTIME_STAT = "gc_runtime_stat"

        /** Self-diagnostic tag for unexpected snapshot construction failures. */
        private const val ERROR_TAG_COLLECT_STATS = "gc_collect_stats"

        /** Self-diagnostic tag for an unexpected scheduled sampling failure. */
        private const val ERROR_TAG_SAMPLE_LOOP = "gc_sample_loop"

        /** Dedicated daemon sampler thread name. */
        private const val THREAD_NAME_SAMPLER = "gc-monitor"

        /** First sample establishes a baseline immediately on the background thread. */
        private const val INITIAL_DELAY_MS = 0L

        /** Prevents invalid configuration from creating a busy loop. */
        private const val MIN_CHECK_INTERVAL_MS = 1_000L

        /** Sentinel distinguishing missing ART counters from a legitimate zero. */
        private const val COUNTER_UNAVAILABLE = -1L

        /** ART cumulative allocated-byte runtime-stat key. */
        private const val STAT_BYTES_ALLOCATED = "art.gc.bytes-allocated"

        /** ART cumulative freed-byte runtime-stat key. */
        private const val STAT_BYTES_FREED = "art.gc.bytes-freed"

        /** ART cumulative GC-count runtime-stat key. */
        private const val STAT_GC_COUNT = "art.gc.gc-count"

        /** ART cumulative GC-time runtime-stat key. */
        private const val STAT_GC_TIME = "art.gc.gc-time"

        /** 模块名。 */
        private const val MODULE_NAME = "gc_monitor"
        /** Memory Churn 告警事件名。 */
        private const val EVENT_MEMORY_CHURN = "memory_churn"
        /** 字段：GC 次数增量。 */
        private const val FIELD_GC_COUNT_DELTA = "gcCountDelta"
        /** 字段：GC 耗时增量。 */
        private const val FIELD_GC_TIME_DELTA_MS = "gcTimeDeltaMs"
        /** Field: whether GC count/time deltas were valid for this window. */
        private const val FIELD_GC_COUNTERS_AVAILABLE = "gcCountersAvailable"
        /** 字段：GC 耗时占比。 */
        private const val FIELD_GC_TIME_RATIO = "gcTimeRatio"
        /** 字段：Heap 增长。 */
        private const val FIELD_HEAP_GROWTH = "heapGrowth"
        /** 字段：当前 Heap 使用率。 */
        private const val FIELD_HEAP_USED_RATIO = "heapUsedRatio"
        /** Field: allocation rate in KiB per second. */
        private const val FIELD_ALLOCATION_RATE_KIB_PER_SEC = "allocationRateKiBPerSec"
        /** Field: reclaimed bytes in the sampling window. */
        private const val FIELD_GC_RECLAIM_BYTES = "gcReclaimBytes"
        /** Field: reclaimed-to-allocated byte ratio. */
        private const val FIELD_GC_RECLAIM_RATE = "gcReclaimRate"
        /** Field: whether allocation rate was valid for this window. */
        private const val FIELD_ALLOCATION_RATE_AVAILABLE = "allocationRateAvailable"
        /** Field: whether reclaim rate was valid for this window. */
        private const val FIELD_RECLAIM_RATE_AVAILABLE = "reclaimRateAvailable"
        /** 字段：检测窗口时长。 */
        private const val FIELD_WINDOW_MS = "windowMs"
        /** 字段：告警原因。 */
        private const val FIELD_REASON = "reason"
    }
}
