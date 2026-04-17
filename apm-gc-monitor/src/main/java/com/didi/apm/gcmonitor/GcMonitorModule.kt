package com.didi.apm.gcmonitor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmContext
import com.didi.apm.core.ApmModule
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity

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
class GcMonitorModule(
    /** 模块配置。 */
    private val config: GcMonitorConfig = GcMonitorConfig()
) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 主线程 Handler，用于定时检测。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false
    /** 上一次 GC 统计快照。 */
    private var lastStats: GcStats? = null

    /** 定时检测任务。 */
    private val checkTask = object : Runnable {
        override fun run() {
            if (!monitoring) return
            checkGc()
            mainHandler.postDelayed(this, config.checkIntervalMs)
        }
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        if (!config.enableGcMonitor) return
        monitoring = true
        // 采集初始快照
        lastStats = collectGcStats()
        // 启动定时检测
        mainHandler.postDelayed(checkTask, config.checkIntervalMs)
        apmContext?.logger?.d("GC monitor started")
    }

    override fun onStop() {
        monitoring = false
        mainHandler.removeCallbacks(checkTask)
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

        // 计算 GC 增量
        val gcCountDelta = current.gcCount - prev.gcCount
        val gcTimeDelta = current.gcTimeMs - prev.gcTimeMs
        val windowMs = current.timestamp - prev.timestamp

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
        if (gcCountDelta >= config.gcCountSpikeThreshold) {
            needReport = true
            reason = "GC count spike: $gcCountDelta in ${windowMs}ms"
        }

        // GC 耗时占比过高
        if (gcTimeRatio >= config.gcTimeRatioThreshold) {
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

        if (needReport) {
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_MEMORY_CHURN,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                fields = mapOf(
                    FIELD_GC_COUNT_DELTA to gcCountDelta,
                    FIELD_GC_TIME_DELTA_MS to gcTimeDelta,
                    FIELD_GC_TIME_RATIO to gcTimeRatio,
                    FIELD_HEAP_GROWTH to heapGrowth,
                    FIELD_HEAP_USED_RATIO to currHeapRatio,
                    FIELD_WINDOW_MS to windowMs,
                    FIELD_REASON to reason
                )
            )
        }

        // 更新快照
        lastStats = current
    }

    /**
     * 采集当前 GC 统计。
     * 通过 Debug.getRuntimeStat 读取 GC 次数和耗时。
     */
    private fun collectGcStats(): GcStats? {
        return try {
            val runtime = Runtime.getRuntime()
            val gcCount = getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
            val gcTimeMs = getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0L
            // gc-time 单位是 ms（API 23+）
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            val heapMax = runtime.maxMemory()

            GcStats(
                gcCount = gcCount,
                gcTimeMs = gcTimeMs,
                javaHeapUsed = heapUsed,
                javaHeapMax = heapMax,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取 Debug.getRuntimeStat。
     * 使用反射调用，兼容不同 API 版本。
     */
    private fun getRuntimeStat(statName: String): String? {
        return try {
            val debugClass = Class.forName("android.os.Debug")
            val method = debugClass.getMethod("getRuntimeStat", String::class.java)
            method.invoke(null, statName) as? String
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "gc_monitor"
        /** Memory Churn 告警事件名。 */
        private const val EVENT_MEMORY_CHURN = "memory_churn"
        /** 字段：GC 次数增量。 */
        private const val FIELD_GC_COUNT_DELTA = "gcCountDelta"
        /** 字段：GC 耗时增量。 */
        private const val FIELD_GC_TIME_DELTA_MS = "gcTimeDeltaMs"
        /** 字段：GC 耗时占比。 */
        private const val FIELD_GC_TIME_RATIO = "gcTimeRatio"
        /** 字段：Heap 增长。 */
        private const val FIELD_HEAP_GROWTH = "heapGrowth"
        /** 字段：当前 Heap 使用率。 */
        private const val FIELD_HEAP_USED_RATIO = "heapUsedRatio"
        /** 字段：检测窗口时长。 */
        private const val FIELD_WINDOW_MS = "windowMs"
        /** 字段：告警原因。 */
        private const val FIELD_REASON = "reason"
    }
}
