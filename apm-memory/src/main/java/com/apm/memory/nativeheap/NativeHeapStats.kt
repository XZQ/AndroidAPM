package com.apm.memory.nativeheap

/**
 * Native Heap 统计数据。
 * 记录 Native 层内存分配的当前值、峰值、总量等。
 */
data class NativeHeapStats(
    /** 当前存活分配字节数。 */
    val currentBytes: Long = 0L,
    /** 历史峰值字节数（PLT Hook 方式可获取，Debug API 不可用）。 */
    val peakBytes: Long = 0L,
    /** 累计分配次数（PLT Hook 方式可获取，Debug API 不可用）。 */
    val allocCount: Long = 0L,
    /** 累计分配字节数。 */
    val totalAllocBytes: Long = 0L,
    /** 累计释放字节数。 */
    val totalFreeBytes: Long = 0L
)
