package com.apm.memory.nativeheap

import android.os.Debug
import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority

/**
 * Native Heap 监控器。
 * 通过 Android Debug API 获取 Native Heap 分配信息（非侵入式）。
 * 生产环境如需 PLT Hook 方式，可集成 bhook 单独替换。
 */
internal class NativeHeapMonitor {

    /** 是否已启用。 */
    private var enabled = false

    /**
     * 启用监控。
     * 当前基于 Debug API，无需额外初始化。
     * @return true 启用成功
     */
    fun enable(): Boolean {
        if (enabled) return true
        enabled = true
        return true
    }

    /**
     * 获取当前 Native Heap 统计数据。
     * @return 统计数据，未启用时返回空
     */
    fun getStats(): NativeHeapStats {
        if (!enabled) return NativeHeapStats()
        return NativeHeapStats(
            currentBytes = Debug.getNativeHeapAllocatedSize(),
            peakBytes = 0L, // Debug API 不直接提供峰值
            allocCount = 0L, // Debug API 不直接提供分配次数
            totalAllocBytes = Debug.getNativeHeapAllocatedSize(),
            totalFreeBytes = Debug.getNativeHeapFreeSize()
        )
    }

    /**
     * 上报一次 Native Heap 统计数据。
     *
     * @param scene 当前场景
     */
    fun reportStats(scene: String) {
        if (!enabled) return
        val stats = getStats()
        Apm.emit(
            module = MODULE,
            name = EVENT_NATIVE_HEAP_STATS,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO, priority = ApmPriority.HIGH,
            scene = scene,
            fields = mapOf(
                "nativeCurrentBytes" to stats.currentBytes,
                "nativeTotalAllocBytes" to stats.totalAllocBytes,
                "nativeTotalFreeBytes" to stats.totalFreeBytes
            )
        )
    }

    /** 禁用监控。 */
    fun disable() {
        enabled = false
    }

    companion object {
        /** 模块名。 */
        private const val MODULE = "memory"
        /** 事件名。 */
        private const val EVENT_NATIVE_HEAP_STATS = "native_heap_stats"
    }
}
