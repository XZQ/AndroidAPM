package com.didi.apm.memory

/**
 * 内存快照数据模型。
 * 一次采样产生的全部内存指标，包括 Java Heap、PSS、Native、系统、GC 等。
 */
data class MemorySnapshot(
    /** 采样时间戳（毫秒）。 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 产生快照的进程名。 */
    val processName: String = "",

    // --- Java Heap (MB) ---
    /** Java Heap 已使用量（MB）。 */
    val javaHeapUsedMb: Long = 0L,
    /** Java Heap 最大容量（MB）。 */
    val javaHeapMaxMb: Long = 0L,
    /** Java Heap 空闲量（MB）。 */
    val javaHeapFreeMb: Long = 0L,

    // --- PSS (KB) ---
    /** 总 PSS（KB），最准确的进程内存占用指标。 */
    val totalPssKb: Int = 0,
    /** Dalvik PSS（KB）。 */
    val dalvikPssKb: Int = 0,
    /** Native PSS（KB）。 */
    val nativePssKb: Int = 0,
    /** 其他 PSS（KB）。 */
    val otherPssKb: Int = 0,
    /** GPU 图形内存 PSS（KB）。 */
    val graphicsPssKb: Int = 0,

    // --- Native Heap (KB) ---
    /** Native Heap 总大小（KB）。 */
    val nativeHeapSizeKb: Long = 0L,
    /** Native Heap 已分配量（KB）。 */
    val nativeHeapAllocatedKb: Long = 0L,
    /** Native Heap 空闲量（KB）。 */
    val nativeHeapFreeKb: Long = 0L,

    // --- 系统内存 (KB) ---
    /** 系统可用内存（KB）。 */
    val systemAvailMemKb: Long = 0L,
    /** 系统总内存（KB）。 */
    val systemTotalMemKb: Long = 0L,
    /** 系统是否处于低内存状态。 */
    val isLowMemory: Boolean = false,
    /** 低内存阈值（KB）。 */
    val lowMemThresholdKb: Long = 0L,

    // --- /proc/self/status (KB) ---
    /** 进程实际物理内存占用 VmRSS（KB）。 */
    val vmRssKb: Long = 0L,
    /** 进程历史峰值 VmPeak（KB）。 */
    val vmPeakKb: Long = 0L,

    // --- GC ---
    /** GC 总次数（API 23+）。 */
    val gcCount: Long = 0L,
    /** GC 总耗时（毫秒，API 23+）。 */
    val gcTimeMs: Long = 0L,

    // --- 场景 ---
    /** 当前场景（如 Activity 类名）。 */
    val scene: String = "unknown",
    /** 是否前台。 */
    val foreground: Boolean = true
)

/**
 * 将快照转换为事件字段 Map。用于 [com.didi.apm.core.Apm.emit] 的 fields 参数。
 */
internal fun MemorySnapshot.toFields(reason: String): Map<String, Any?> {
    return linkedMapOf(
        "reason" to reason,
        "javaHeapUsedMb" to javaHeapUsedMb,
        "javaHeapMaxMb" to javaHeapMaxMb,
        "javaHeapFreeMb" to javaHeapFreeMb,
        "totalPssKb" to totalPssKb,
        "dalvikPssKb" to dalvikPssKb,
        "nativePssKb" to nativePssKb,
        "otherPssKb" to otherPssKb,
        "graphicsPssKb" to graphicsPssKb,
        "nativeHeapSizeKb" to nativeHeapSizeKb,
        "nativeHeapAllocatedKb" to nativeHeapAllocatedKb,
        "nativeHeapFreeKb" to nativeHeapFreeKb,
        "systemAvailMemKb" to systemAvailMemKb,
        "systemTotalMemKb" to systemTotalMemKb,
        "isLowMemory" to isLowMemory,
        "lowMemThresholdKb" to lowMemThresholdKb,
        "vmRssKb" to vmRssKb,
        "vmPeakKb" to vmPeakKb,
        "gcCount" to gcCount,
        "gcTimeMs" to gcTimeMs
    )
}
