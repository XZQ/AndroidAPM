package com.didi.apm.gcmonitor

/**
 * GC 统计数据快照。
 * 记录某次采样点的 GC 次数、耗时、内存分配速率等。
 */
data class GcStats(
    /** GC 总次数。 */
    val gcCount: Long = 0L,
    /** GC 总耗时（毫秒）。 */
    val gcTimeMs: Long = 0L,
    /** Java Heap 已使用量（字节）。 */
    val javaHeapUsed: Long = 0L,
    /** Java Heap 最大值（字节）。 */
    val javaHeapMax: Long = 0L,
    /** 采样时间戳。 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 对象分配速率（KB/秒），通过 heap delta 推算。 */
    val allocationRateKbPerSec: Float = 0f,
    /** GC 回收字节数。 */
    val gcReclaimBytes: Long = 0L,
    /** GC 回收率（0-1）。 */
    val gcReclaimRate: Float = 0f,
    /** 累计分配字节。 */
    val bytesAllocated: Long = 0L,
    /** 累计释放字节。 */
    val bytesFreed: Long = 0L
)
