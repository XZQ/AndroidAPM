package com.apm.core.selfmonitor

import com.apm.model.ApmPriority
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fixed dispatcher stages whose worker latency is reported without business payload.
 *
 * @property fieldName stable lowercase suffix used by SDK health fields
 */
internal enum class DispatcherStage(
    val fieldName: String
) {
    /** Lazy event construction and immutable event resolution. */
    RESOLVE("resolve"),

    /** Signed dynamic sampling policy evaluation. */
    SAMPLING("sampling"),

    /** Optional aggregation or alert deduplication. */
    AGGREGATE("aggregate"),

    /** Dynamic and local rate-limit evaluation. */
    RATE_LIMIT("rateLimit"),

    /** Optional PII sanitization before persistence. */
    SANITIZE("sanitize"),

    /** Batch append until the event store returns its ownership hand-off result. */
    STORE_HANDOFF("storeHandoff")
}

/**
 * Coherent bounded histogram for one dispatcher stage.
 *
 * The dispatcher has one worker, so recording normally enters an uncontended short critical
 * section. The once-per-report snapshot uses the same monitor to avoid interval-boundary count,
 * sum, histogram, and max mismatches.
 */
private class DispatcherStageLatencyAccumulator {
    /** Number of stage invocations in the current report interval. */
    private var sampleCount = 0L

    /** Sum of non-negative stage durations in nanoseconds. */
    private var totalNanos = 0L

    /** Maximum stage duration in nanoseconds. */
    private var maxNanos = 0L

    /** Fixed histogram counts aligned with [DISPATCHER_STAGE_LATENCY_BUCKET_UPPER_BOUNDS_NANOS]. */
    private val bucketCounts = LongArray(DISPATCHER_STAGE_LATENCY_BUCKET_UPPER_BOUNDS_NANOS.size)

    /**
     * Records one stage duration with no allocation.
     *
     * @param elapsedNanos measured monotonic duration; negative values are defensively clamped
     */
    @Synchronized
    fun record(elapsedNanos: Long) {
        val safeNanos = elapsedNanos.coerceAtLeast(0L)
        sampleCount += 1L
        totalNanos += safeNanos
        maxNanos = maxOf(maxNanos, safeNanos)
        val searchResult = DISPATCHER_STAGE_LATENCY_BUCKET_UPPER_BOUNDS_NANOS.binarySearch(safeNanos)
        val bucketIndex = if (searchResult >= 0) searchResult else -searchResult - 1
        bucketCounts[bucketIndex] += 1L
    }

    /**
     * Returns one coherent interval snapshot and resets all bounded state.
     *
     * @return numeric latency evidence in microseconds
     */
    @Synchronized
    fun snapshotAndReset(): DispatcherStageLatencyReport {
        val report = if (sampleCount == 0L) {
            DispatcherStageLatencyReport()
        } else {
            val percentileRank = sampleCount - sampleCount / P95_DENOMINATOR
            var cumulativeCount = 0L
            var p95UpperBoundNanos = maxNanos
            // The first bucket reaching the rank is the conservative percentile upper bound.
            for (index in bucketCounts.indices) {
                cumulativeCount += bucketCounts[index]
                if (cumulativeCount >= percentileRank) {
                    val bucketUpperBound = DISPATCHER_STAGE_LATENCY_BUCKET_UPPER_BOUNDS_NANOS[index]
                    p95UpperBoundNanos = if (bucketUpperBound == Long.MAX_VALUE) {
                        maxNanos
                    } else {
                        bucketUpperBound
                    }
                    break
                }
            }
            DispatcherStageLatencyReport(
                sampleCount = sampleCount,
                averageMicros = nanosToCeilingMicros(ceilingAverage(totalNanos, sampleCount)),
                p95UpperBoundMicros = nanosToCeilingMicros(p95UpperBoundNanos),
                maxMicros = nanosToCeilingMicros(maxNanos)
            )
        }
        sampleCount = 0L
        totalNanos = 0L
        maxNanos = 0L
        bucketCounts.fill(0L)
        return report
    }

    companion object {
        /** Divisor used by `count - floor(count / 20)` to compute the ceil(95%) rank. */
        private const val P95_DENOMINATOR = 20L

        /** Returns a ceiling average without losing a positive sub-nanosecond remainder. */
        private fun ceilingAverage(total: Long, count: Long): Long {
            val quotient = total / count
            return if (total % count == 0L) quotient else quotient + 1L
        }

        /** Converts a non-negative nanosecond duration to ceiling microseconds. */
        private fun nanosToCeilingMicros(nanos: Long): Long {
            return if (nanos <= 0L) 0L else 1L + (nanos - 1L) / NANOS_PER_MICROSECOND
        }
    }
}

/** Fixed nanosecond histogram upper bounds from 1 microsecond through about 1.05 seconds. */
private val DISPATCHER_STAGE_LATENCY_BUCKET_UPPER_BOUNDS_NANOS = longArrayOf(
    1_000L,
    2_000L,
    4_000L,
    8_000L,
    16_000L,
    32_000L,
    64_000L,
    128_000L,
    256_000L,
    512_000L,
    1_024_000L,
    2_048_000L,
    4_096_000L,
    8_192_000L,
    16_384_000L,
    32_768_000L,
    65_536_000L,
    131_072_000L,
    262_144_000L,
    524_288_000L,
    1_048_576_000L,
    Long.MAX_VALUE
)

/** Nanoseconds per microsecond for integer health-field conversion. */
private const val NANOS_PER_MICROSECOND = 1_000L

/**
 * SDK 自监控组件。
 * 跟踪 APM 框架自身运行指标，用于：
 * - 评估 SDK 对宿主应用的性能影响
 * - 驱动 [AutoThrottle] 自动降级策略
 * - 定期生成 [SdkHealthReport] 上报
 *
 * 线程安全：普通计数器使用 Atomic 类型；dispatcher 阶段直方图使用单 worker 下
 * 无竞争的短同步区间，并与报告线程生成一致快照。
 */
class SdkSelfMonitor(private val reportIntervalMs: Long = DEFAULT_REPORT_INTERVAL_MS) {
    /** 发射事件计数。 */
    private val emitCount = AtomicLong(0L)

    /** 丢弃事件计数（限流、队列满等）。 */
    private val dropCount = AtomicLong(0L)

    /** Dispatcher high-water drops caused specifically by per-module isolation. */
    private val dispatcherModuleIsolationDropCount = AtomicLong(0L)

    /** Per-reason loss counters indexed by stable enum ordinal. */
    private val dropReasonCounts = Array(SdkDropReason.values().size) { AtomicLong(0L) }

    /** Per-priority loss counters indexed by stable enum ordinal. */
    private val dropPriorityCounts = Array(ApmPriority.values().size) { AtomicLong(0L) }

    /** Losses whose historical priority was unavailable at the observation boundary. */
    private val unattributedDropPriorityCount = AtomicLong(0L)

    /** 上传延迟累计（毫秒），用于计算平均值。 */
    private val totalUploadLatencyMs = AtomicLong(0L)

    /** 上传次数计数，用于计算平均延迟。 */
    private val uploadCount = AtomicLong(0L)

    /** 最大单次上传延迟（毫秒）。 */
    private val maxUploadLatencyMs = AtomicLong(0L)

    /** 当前队列大小快照（由外部定期更新）。 */
    private val currentQueueSize = AtomicInteger(0)

    /** Current dispatcher retained-byte reservation snapshot. */
    private val currentQueueBytes = AtomicLong(0L)

    /** SDK 内部错误计数（监控模块捕获并降级处理的异常）。 */
    private val internalErrorCount = AtomicLong(0L)

    /** Bounded latency histograms indexed by [DispatcherStage.ordinal]. */
    private val dispatcherStageLatencyAccumulators =
        Array(DispatcherStage.values().size) { DispatcherStageLatencyAccumulator() }

    /**
     * 记录一次事件发射。
     * 每次 [com.apm.core.Apm.emit] 被调用时计数 +1。
     */
    fun recordEmit() {
        emitCount.incrementAndGet()
    }

    /**
     * 记录一次事件丢弃。
     * 限流拦截、队列满丢弃时调用。
     *
     * @param priority 被丢弃事件的优先级，用于分类统计
     * @param reason stable loss reason
     */
    fun recordDrop(
        priority: ApmPriority = ApmPriority.NORMAL,
        reason: SdkDropReason = SdkDropReason.UNCLASSIFIED
    ) {
        recordDrops(1, priority, reason)
    }

    /**
     * 批量记录存储容量淘汰等已知数量的事件丢弃。
     *
     * @param count 本次丢弃数量；非正数被忽略
     * @param priority exact priority, or null when an upstream boundary exposed only an aggregate
     * @param reason stable loss reason
     */
    fun recordDrops(
        count: Int,
        priority: ApmPriority? = null,
        reason: SdkDropReason = SdkDropReason.UNCLASSIFIED
    ) {
        if (count <= 0) {
            return
        }
        val delta = count.toLong()
        dropCount.addAndGet(delta)
        dropReasonCounts[reason.ordinal].addAndGet(delta)
        if (priority == null) {
            unattributedDropPriorityCount.addAndGet(delta)
        } else {
            dropPriorityCounts[priority.ordinal].addAndGet(delta)
        }
    }

    /**
     * Records an aggregate loss with every exact priority still visible at the boundary.
     *
     * Any difference between [totalCount] and [priorityCounts] is assigned to the explicit
     * `UNATTRIBUTED` bucket instead of inventing a priority.
     *
     * @param totalCount complete number of removed events
     * @param priorityCounts non-negative exact counts by known priority
     * @param reason shared reason for the removal
     */
    fun recordDropsByPriority(
        totalCount: Int,
        priorityCounts: Map<ApmPriority, Int>,
        reason: SdkDropReason
    ) {
        if (totalCount <= 0) {
            return
        }
        var attributed = 0
        for ((priority, rawCount) in priorityCounts) {
            val remaining = totalCount - attributed
            if (remaining <= 0) {
                break
            }
            val count = rawCount.coerceIn(0, remaining)
            recordDrops(count, priority, reason)
            attributed += count
        }
        recordDrops(totalCount - attributed, priority = null, reason = reason)
    }

    /**
     * Records one dispatcher drop caused by per-module noisy-neighbor isolation.
     *
     * The event also contributes to the aggregate drop count so existing health consumers keep
     * their complete loss-rate view.
     *
     * @param priority priority of the isolated event
     */
    fun recordDispatcherModuleIsolationDrop(priority: ApmPriority = ApmPriority.NORMAL) {
        dispatcherModuleIsolationDropCount.incrementAndGet()
        recordDrop(priority, SdkDropReason.DISPATCHER_MODULE_ISOLATION)
    }

    /**
     * 记录一次上传延迟。
     * 每次上传完成时调用，延迟为提交到上传完成的时间差。
     *
     * @param ms 上传延迟毫秒数
     */
    fun recordUploadLatency(ms: Long) {
        totalUploadLatencyMs.addAndGet(ms)
        uploadCount.incrementAndGet()
        // CAS 更新最大延迟
        var prev: Long
        do {
            prev = maxUploadLatencyMs.get()
            if (ms <= prev) {
                break
            }
        } while (!maxUploadLatencyMs.compareAndSet(prev, ms))
    }

    /**
     * 记录一次 SDK 内部错误。
     * 监控模块捕获异常并降级处理时调用，使"静默吞异常"变得可观测。
     *
     * @param tag 错误来源标签（如 "ipc_write"、"fps_frame_metrics_register"）
     */
    fun recordInternalError(tag: String) {
        internalErrorCount.incrementAndGet()
    }

    /**
     * 更新队列大小快照。
     * 由分发器定期调用，反映当前积压程度。
     *
     * @param size 当前队列中的事件数
     */
    fun updateQueueSize(size: Int) {
        currentQueueSize.set(size)
    }

    /** Updates count and retained-byte pressure snapshots from the dispatcher. */
    fun updateQueuePressure(size: Int, bytes: Long) {
        currentQueueSize.set(size.coerceAtLeast(0))
        currentQueueBytes.set(bytes.coerceAtLeast(0L))
    }

    /**
     * Records one measured dispatcher worker stage.
     *
     * This is internal so SDK integrations cannot inject arbitrary health dimensions.
     *
     * @param stage fixed dispatcher stage
     * @param elapsedNanos non-negative monotonic duration
     */
    internal fun recordDispatcherStageLatency(stage: DispatcherStage, elapsedNanos: Long) {
        dispatcherStageLatencyAccumulators[stage.ordinal].record(elapsedNanos)
    }

    /**
     * 生成当前周期的健康报告。
     * 读取所有计数器快照并重置归零（用于下一周期）。
     *
     * @return 当前周期的健康报告
     */
    fun generateReport(): SdkHealthReport {
        // 读取并重置计数器
        val emit = emitCount.getAndSet(0L)
        val drop = dropCount.getAndSet(0L)
        val dispatcherIsolationDrops = dispatcherModuleIsolationDropCount.getAndSet(0L)
        val reasonDrops = SdkDropReason.values().associate { reason ->
            reason.name to dropReasonCounts[reason.ordinal].getAndSet(0L)
        }
        val priorityDrops = ApmPriority.values().associate { priority ->
            priority.name to dropPriorityCounts[priority.ordinal].getAndSet(0L)
        } + (UNATTRIBUTED_PRIORITY to unattributedDropPriorityCount.getAndSet(0L))
        val totalLatency = totalUploadLatencyMs.getAndSet(0L)
        val uploads = uploadCount.getAndSet(0L)
        val maxLatency = maxUploadLatencyMs.getAndSet(0L)
        val internalErrors = internalErrorCount.getAndSet(0L)
        val dispatcherStageLatencies = DispatcherStage.values().associate { stage ->
            stage.fieldName to dispatcherStageLatencyAccumulators[stage.ordinal].snapshotAndReset()
        }

        // 计算平均延迟
        val avgLatency = if (uploads > 0L) totalLatency / uploads else 0L

        return SdkHealthReport(
            emitCount = emit,
            dropCount = drop,
            queueSize = currentQueueSize.get(),
            queueBytes = currentQueueBytes.get(),
            avgUploadLatencyMs = avgLatency,
            maxUploadLatencyMs = maxLatency,
            internalErrorCount = internalErrors,
            dispatcherModuleIsolationDropCount = dispatcherIsolationDrops,
            dropCountsByReason = reasonDrops,
            dropCountsByPriority = priorityDrops,
            dispatcherStageLatencies = dispatcherStageLatencies
        )
    }

    /**
     * 获取累计发射事件数（非重置）。
     * 用于外部查询当前总发射量。
     */
    fun getTotalEmitCount(): Long = emitCount.get()

    /**
     * 获取累计丢弃事件数（非重置）。
     * 用于外部查询当前总丢弃量。
     */
    fun getTotalDropCount(): Long = dropCount.get()

    /** Returns the current-period dispatcher module-isolation drop count without resetting it. */
    fun getTotalDispatcherModuleIsolationDropCount(): Long =
        dispatcherModuleIsolationDropCount.get()

    /** Returns the current-period count for one stable loss reason without resetting it. */
    fun getDropCount(reason: SdkDropReason): Long = dropReasonCounts[reason.ordinal].get()

    /** Returns the current-period count for one event priority without resetting it. */
    fun getDropCount(priority: ApmPriority): Long = dropPriorityCounts[priority.ordinal].get()

    /** Returns current losses whose priority was unavailable without resetting the counter. */
    fun getUnattributedDropPriorityCount(): Long = unattributedDropPriorityCount.get()

    /**
     * 获取累计内部错误数（非重置）。
     * 用于外部查询监控模块降级处理的异常总量。
     */
    fun getTotalInternalErrorCount(): Long = internalErrorCount.get()

    companion object {
        /** 默认报告间隔：60 秒。 */
        private const val DEFAULT_REPORT_INTERVAL_MS = 60_000L
        /** Stable priority-map bucket used only when an upstream aggregate omitted priority. */
        const val UNATTRIBUTED_PRIORITY = "UNATTRIBUTED"
    }
}
