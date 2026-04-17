package com.didi.apm.memory.oom

import com.didi.apm.core.Apm
import com.didi.apm.core.ApmLogger
import com.didi.apm.memory.MemoryConfig
import com.didi.apm.memory.MemorySnapshot
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * OOM 预警监控器。
 * 每次采样后检查 Java Heap 使用率、系统低内存、Native Heap 等指标，
 * 超过阈值时触发告警和可选的 hprof dump。
 *
 * 线程安全：使用 Atomic 类型保证并发采样场景下的状态一致性。
 */
internal class OomMonitor(
    /** 内存模块配置。 */
    private val config: MemoryConfig,
    /** Hprof dump 器，可选。 */
    private val hprofDumper: HprofDumper?
) {
    /** 上一次 dump 的时间戳，用于冷却控制。 */
    private val lastDumpTime = AtomicLong(0L)
    /** 是否已触发过 dump（首次不冷却）。 */
    private val hasTriggeredDump = AtomicBoolean(false)

    /**
     * 检查一次快照是否触发 OOM 预警。
     * 三个维度独立检查：Java Heap、系统低内存、Native Heap。
     */
    fun check(snapshot: MemorySnapshot) {
        checkJavaHeapThreshold(snapshot)
        checkSystemLowMemory(snapshot)
        checkNativeHeapThreshold(snapshot)
    }

    /**
     * 检查 Java Heap 使用率。
     * - >= criticalRatio：触发 dump + ERROR 告警
     * - >= warnRatio：仅 WARN 告警
     */
    private fun checkJavaHeapThreshold(snapshot: MemorySnapshot) {
        if (snapshot.javaHeapMaxMb <= 0L) return
        val ratio = snapshot.javaHeapUsedMb.toFloat() / snapshot.javaHeapMaxMb

        when {
            ratio >= config.javaHeapCriticalRatio -> {
                // 危险级别：发送 ERROR 告警 + 触发 dump
                Apm.emit(
                    module = MODULE,
                    name = EVENT_OOM_CRITICAL,
                    kind = ApmEventKind.ALERT,
                    severity = ApmSeverity.ERROR,
                    scene = snapshot.scene,
                    foreground = snapshot.foreground,
                    fields = mapOf(
                        "javaHeapRatio" to "%.2f".format(ratio),
                        "javaHeapUsedMb" to snapshot.javaHeapUsedMb,
                        "javaHeapMaxMb" to snapshot.javaHeapMaxMb
                    )
                )
                triggerDump("java_heap_critical_${"%.2f".format(ratio)}")
            }
            ratio >= config.javaHeapWarnRatio -> {
                // 警告级别：仅发送告警
                Apm.emit(
                    module = MODULE,
                    name = EVENT_OOM_WARN,
                    kind = ApmEventKind.ALERT,
                    severity = ApmSeverity.WARN,
                    scene = snapshot.scene,
                    foreground = snapshot.foreground,
                    fields = mapOf(
                        "javaHeapRatio" to "%.2f".format(ratio),
                        "javaHeapUsedMb" to snapshot.javaHeapUsedMb,
                        "javaHeapMaxMb" to snapshot.javaHeapMaxMb
                    )
                )
            }
        }
    }

    /**
     * 检查系统低内存状态。
     * - isLowMemory=true：发送低内存告警
     * - 可用内存 < 1.5 倍阈值：发送预警
     */
    private fun checkSystemLowMemory(snapshot: MemorySnapshot) {
        // 系统已标记为低内存
        if (snapshot.isLowMemory) {
            Apm.emit(
                module = MODULE,
                name = EVENT_SYSTEM_LOW_MEMORY,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                scene = snapshot.scene,
                foreground = snapshot.foreground,
                fields = mapOf(
                    "systemAvailMemKb" to snapshot.systemAvailMemKb,
                    "lowMemThresholdKb" to snapshot.lowMemThresholdKb
                )
            )
        }
        // 可用内存接近阈值（1.5 倍以内）
        if (snapshot.systemAvailMemKb > 0 &&
            snapshot.lowMemThresholdKb > 0 &&
            snapshot.systemAvailMemKb < snapshot.lowMemThresholdKb * SYSTEM_MEM_WARN_MULTIPLIER / SYSTEM_MEM_WARN_DIVISOR
        ) {
            Apm.emit(
                module = MODULE,
                name = EVENT_SYSTEM_MEM_WARN,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                scene = snapshot.scene,
                foreground = snapshot.foreground,
                fields = mapOf(
                    "systemAvailMemKb" to snapshot.systemAvailMemKb,
                    "lowMemThresholdKb" to snapshot.lowMemThresholdKb
                )
            )
        }
    }

    /**
     * 检查 Native Heap 分配量是否超阈值。
     */
    private fun checkNativeHeapThreshold(snapshot: MemorySnapshot) {
        if (snapshot.nativeHeapAllocatedKb > config.nativeHeapWarnKb) {
            Apm.emit(
                module = MODULE,
                name = EVENT_NATIVE_HEAP_WARN,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                scene = snapshot.scene,
                foreground = snapshot.foreground,
                fields = mapOf(
                    "nativeHeapAllocatedKb" to snapshot.nativeHeapAllocatedKb,
                    "nativeHeapWarnKb" to config.nativeHeapWarnKb
                )
            )
        }
    }

    /**
     * 触发 hprof dump。
     * 使用 CAS 保证并发安全，冷却期内不重复 dump。
     */
    private fun triggerDump(reason: String) {
        val dumper = hprofDumper ?: return
        if (!config.enableHprofDump) return
        val now = System.currentTimeMillis()
        val last = lastDumpTime.get()
        // 冷却期内跳过
        if (hasTriggeredDump.get() && now - last < config.dumpCooldownMs) return
        // CAS 保证只有一个线程能触发
        if (!lastDumpTime.compareAndSet(last, now)) return
        hasTriggeredDump.set(true)
        dumper.dumpAsync(reason)
    }

    companion object {
        /** 模块名。 */
        private const val MODULE = "memory"
        /** OOM 危险事件名。 */
        private const val EVENT_OOM_CRITICAL = "oom_critical"
        /** OOM 预警事件名。 */
        private const val EVENT_OOM_WARN = "oom_warn"
        /** 系统低内存事件名。 */
        private const val EVENT_SYSTEM_LOW_MEMORY = "system_low_memory"
        /** 系统内存预警事件名。 */
        private const val EVENT_SYSTEM_MEM_WARN = "system_mem_warn"
        /** Native Heap 预警事件名。 */
        private const val EVENT_NATIVE_HEAP_WARN = "native_heap_warn"
        /** 系统内存预警乘数。 */
        private const val SYSTEM_MEM_WARN_MULTIPLIER = 3
        /** 系统内存预警除数。 */
        private const val SYSTEM_MEM_WARN_DIVISOR = 2
    }
}
