package com.didi.apm.memory

import com.didi.apm.core.Apm
import com.didi.apm.memory.leak.LeakResult
import com.didi.apm.memory.leak.LeakType
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity

/**
 * 内存事件上报器。
 * 负责将内存快照、告警、泄漏检测结果通过 APM 管道上报。
 */
internal class MemoryReporter(
    /** 内存模块配置。 */
    private val config: MemoryConfig
) {
    /**
     * 处理一次内存快照。
     * 执行流程：
     * 1. 如果配置了每条都上报，发送 METRIC 事件
     * 2. 检查是否触发告警（Java Heap 超阈值 / PSS 超阈值 / 系统低内存）
     * 3. 告警事件额外携带告警原因和 Heap 使用率
     */
    fun onSnapshot(snapshot: MemorySnapshot, reason: String) {
        // 计算 Java Heap 使用率
        val heapRatio = if (snapshot.javaHeapMaxMb <= 0L) 0f
        else snapshot.javaHeapUsedMb.toFloat() / snapshot.javaHeapMaxMb.toFloat()

        // 每条快照都上报
        if (config.reportEverySnapshot) {
            Apm.emit(
                module = MODULE,
                name = SNAPSHOT_EVENT,
                kind = ApmEventKind.METRIC,
                severity = ApmSeverity.INFO,
                scene = snapshot.scene,
                foreground = snapshot.foreground,
                fields = snapshot.toFields(reason)
            )
        }

        // 收集告警原因
        val alertReasons = mutableListOf<String>()
        if (heapRatio >= config.javaHeapWarnRatio) {
            alertReasons += ALERT_REASON_JAVA_HEAP
        }
        if (snapshot.totalPssKb >= config.totalPssWarnKb) {
            alertReasons += ALERT_REASON_TOTAL_PSS
        }
        if (snapshot.isLowMemory) {
            alertReasons += ALERT_REASON_LOW_MEMORY
        }

        // 存在告警原因时发送 ALERT 事件
        if (alertReasons.isNotEmpty()) {
            Apm.emit(
                module = MODULE,
                name = ALERT_EVENT,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                scene = snapshot.scene,
                foreground = snapshot.foreground,
                fields = snapshot.toFields(reason) + mapOf(
                    "alertReasons" to alertReasons.joinToString(","),
                    "javaHeapRatio" to "%.2f".format(heapRatio)
                )
            )
        }
    }

    /**
     * 上报泄漏检测结果。
     *
     * @param result 泄漏检测结果
     */
    fun onLeakFound(result: LeakResult) {
        Apm.emit(
            module = MODULE,
            name = LEAK_EVENT,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN,
            scene = result.scene,
            fields = mapOf(
                "leakClass" to result.leakClass,
                "leakType" to result.type.name,
                "retainedCount" to result.retainedCount,
                "suspectFields" to result.suspectFields.joinToString(",")
            )
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE = "memory"
        /** 快照事件名。 */
        private const val SNAPSHOT_EVENT = "memory_snapshot"
        /** 告警事件名。 */
        private const val ALERT_EVENT = "memory_alert"
        /** 泄漏事件名。 */
        private const val LEAK_EVENT = "memory_leak"
        /** 告警原因：Java Heap 超阈值。 */
        private const val ALERT_REASON_JAVA_HEAP = "java_heap_ratio"
        /** 告警原因：PSS 总量超阈值。 */
        private const val ALERT_REASON_TOTAL_PSS = "total_pss"
        /** 告警原因：系统低内存。 */
        private const val ALERT_REASON_LOW_MEMORY = "system_low_memory"
    }
}
