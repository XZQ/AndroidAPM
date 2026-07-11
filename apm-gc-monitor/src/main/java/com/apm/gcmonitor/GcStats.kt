package com.apm.gcmonitor

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
    /** Object allocation rate in KiB/second, derived from ART counter deltas. */
    val allocationRateKbPerSec: Float = 0f,
    /** Bytes reclaimed during the latest completed sampling window. */
    val gcReclaimBytes: Long = 0L,
    /** Latest reclaimed-to-allocated byte ratio (0-1). */
    val gcReclaimRate: Float = 0f,
    /** 累计分配字节。 */
    val bytesAllocated: Long = 0L,
    /** 累计释放字节。 */
    val bytesFreed: Long = 0L
)

/** Derived metrics for one pair of monotonic ART runtime-stat snapshots. */
internal data class GcWindowMetrics(
    /** Whether GC count/time counters were valid and monotonic across the window. */
    val gcCountersAvailable: Boolean,
    /** GC count increase in the window, or zero when unavailable/reset. */
    val gcCountDelta: Long,
    /** GC time increase in the window, or zero when unavailable/reset. */
    val gcTimeDeltaMs: Long,
    /** Whether allocation counters were valid and monotonic across the window. */
    val allocationRateAvailable: Boolean,
    /** Whether allocation and freed counters both supported reclaim analysis. */
    val reclaimRateAvailable: Boolean,
    /** Allocated bytes per second, expressed in KiB. */
    val allocationRateKbPerSec: Float,
    /** Bytes reclaimed during the window. */
    val gcReclaimBytes: Long,
    /** Reclaimed bytes divided by allocated bytes, clamped to 0..1. */
    val gcReclaimRate: Float,
    /** Bytes allocated during the window. */
    val allocatedBytes: Long
) {
    companion object {
        /** Calculates reset-safe metrics from cumulative counters. */
        internal fun calculate(previous: GcStats, current: GcStats): GcWindowMetrics {
            val windowMs = (current.timestamp - previous.timestamp).coerceAtLeast(0L)
            val gcCountersAvailable = windowMs > 0L &&
                previous.gcCount >= 0L && current.gcCount >= previous.gcCount &&
                previous.gcTimeMs >= 0L && current.gcTimeMs >= previous.gcTimeMs
            val allocationRateAvailable = windowMs > 0L &&
                previous.bytesAllocated >= 0L &&
                current.bytesAllocated >= previous.bytesAllocated
            val reclaimRateAvailable = allocationRateAvailable &&
                previous.bytesFreed >= 0L &&
                current.bytesFreed >= previous.bytesFreed
            val allocatedBytes = if (allocationRateAvailable) {
                current.bytesAllocated - previous.bytesAllocated
            } else {
                0L
            }
            val reclaimedBytes = if (reclaimRateAvailable) {
                current.bytesFreed - previous.bytesFreed
            } else {
                0L
            }
            val allocationRate = if (allocationRateAvailable) {
                allocatedBytes.toDouble() * MILLIS_PER_SECOND / windowMs / BYTES_PER_KIB
            } else {
                0.0
            }
            val reclaimRate = if (reclaimRateAvailable && allocatedBytes > 0L) {
                (reclaimedBytes.toDouble() / allocatedBytes).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            return GcWindowMetrics(
                gcCountersAvailable = gcCountersAvailable,
                gcCountDelta = if (gcCountersAvailable) current.gcCount - previous.gcCount else 0L,
                gcTimeDeltaMs = if (gcCountersAvailable) current.gcTimeMs - previous.gcTimeMs else 0L,
                allocationRateAvailable = allocationRateAvailable,
                reclaimRateAvailable = reclaimRateAvailable,
                allocationRateKbPerSec = allocationRate.toFloat(),
                gcReclaimBytes = reclaimedBytes,
                gcReclaimRate = reclaimRate.toFloat(),
                allocatedBytes = allocatedBytes
            )
        }

        /** Milliseconds per second. */
        private const val MILLIS_PER_SECOND = 1_000.0

        /** Bytes per kibibyte. */
        private const val BYTES_PER_KIB = 1_024.0
    }
}
