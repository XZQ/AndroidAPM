package com.apm.core.aggregation

import com.apm.core.ApmClock
import com.apm.core.canSkipDebugLogs
import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.core.ApmLogger

/**
 * 客户端事件聚合器。
 *
 * 在滑动窗口内对高频 METRIC 事件进行聚合：
 * - 数值字段计算 P50/P90/P99/min/max/count
 * - 聚合后输出一条 [AggregatedEvent]，大幅减少上报量
 *
 * ALERT 类事件（crash/ANR）通过 [StackFingerprinter] 去重。
 * FILE 类事件不聚合，直接上报。
 *
 * 线程安全：所有方法通过 synchronized 保护内部状态。
 */
class EventAggregator(
    /** 聚合窗口时长（毫秒）。 */
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    /** 是否启用聚合。 */
    private val enabled: Boolean = true,
    /** 日志接口。 */
    private val logger: ApmLogger? = null,
    /** Maximum simultaneously active module/name buckets. */
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
    /** Maximum percentile samples retained per numeric field. */
    private val maxSamplesPerField: Int = DEFAULT_MAX_SAMPLES_PER_FIELD
) {
    /** 栈指纹去重器，用于 ALERT 类事件去重。 */
    private val stackFingerprinter = StackFingerprinter()

    /**
     * 高频路径 debug 日志守卫：内置 logger 关闭 debug 输出时跳过模板构串
     * （此时 d() 是可证明的空操作）；自定义 logger 恒为 false，行为不变。
     */
    private val skipDebugLogs = canSkipDebugLogs(logger)

    /**
     * 活跃的聚合桶。
     * key = "${module}/${name}"，value = 该桶的采样数据。
     */
    private val buckets = LinkedHashMap<String, AggregationBucket>()

    /** Aggregation window exposed to the dispatch scheduler. */
    val windowDurationMs: Long
        get() = windowMs

    /**
     * 处理一个事件，返回应该上报的事件列表。
     *
     * @param event 原始事件
     * @return 需要上报的事件列表（可能是原始事件、聚合后的事件、或空列表）
     */
    @Synchronized
    fun process(event: ApmEvent): List<ApmEvent> {
        if (!enabled) {
            return listOf(event)
        }

        return when (event.kind) {
            // METRIC 事件进入聚合桶
            ApmEventKind.METRIC -> aggregateMetric(event)

            // ALERT 事件做栈指纹去重
            ApmEventKind.ALERT -> deduplicateAlert(event)

            // FILE 事件不聚合，直接上报
            ApmEventKind.FILE -> listOf(event)
        }
    }

    /**
     * 刷出所有活跃桶的聚合结果。
     * 用于应用退出或定时刷出时调用。
     *
     * @return 所有待上报的聚合事件
     */
    @Synchronized
    fun flush(): List<ApmEvent> {
        if (buckets.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<ApmEvent>()
        val nowTimestampMs = ApmClock.wallTimeMillis()

        for ((key, bucket) in buckets) {
            if (bucket.eventCount > 0) {
                val aggregated = bucket.toAggregatedEvent(nowTimestampMs)
                results.add(aggregated.toApmEvent())
                logger?.d("Flushed aggregation bucket $key: ${bucket.eventCount} events")
            }
        }

        buckets.clear()
        return results
    }

    /**
     * Flushes only buckets whose aggregation window has expired.
     *
     * @param nowElapsedMs current monotonic time
     * @return expired aggregate events
     */
    @Synchronized
    fun flushExpired(nowElapsedMs: Long = ApmClock.monotonicTimeMillis()): List<ApmEvent> {
        val results = mutableListOf<ApmEvent>()
        val nowTimestampMs = ApmClock.wallTimeMillis()
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val (key, bucket) = iterator.next()
            if (nowElapsedMs - bucket.windowStartElapsedMs < windowMs) {
                continue
            }
            iterator.remove()
            if (bucket.eventCount > 0) {
                results += bucket.toAggregatedEvent(nowTimestampMs).toApmEvent()
            }
        }
        return results
    }

    /**
     * 处理 METRIC 事件聚合。
     * 如果窗口未满，吞入事件返回空列表；如果窗口到期，输出聚合结果。
     */
    private fun aggregateMetric(event: ApmEvent): List<ApmEvent> {
        val bucketKey = "${event.module}/${event.name}"
        val nowElapsedMs = ApmClock.monotonicTimeMillis()
        val nowTimestampMs = ApmClock.wallTimeMillis()
        // 常见路径是事件被吞入桶且无桶到期，惰性分配避免每事件一次 ArrayList
        var output: MutableList<ApmEvent>? = null

        fun emitAggregated(aggregated: ApmEvent) {
            val target = output ?: mutableListOf<ApmEvent>().also { output = it }
            target += aggregated
        }

        // 获取或创建聚合桶
        var bucket = buckets[bucketKey]
        if (bucket == null) {
            if (buckets.size >= maxBuckets.coerceAtLeast(1)) {
                val eldest = buckets.entries.firstOrNull()
                if (eldest != null) {
                    buckets.remove(eldest.key)
                    if (eldest.value.eventCount > 0) {
                        emitAggregated(eldest.value.toAggregatedEvent(nowTimestampMs).toApmEvent())
                    }
                }
            }
            bucket = AggregationBucket(
                module = event.module,
                name = event.name,
                windowStartTimestampMs = nowTimestampMs,
                windowStartElapsedMs = nowElapsedMs,
                maxSamplesPerField = maxSamplesPerField
            )
            buckets[bucketKey] = bucket
        }

        // 将事件的数值字段加入桶
        bucket.addSample(event.fields)

        // 检查窗口是否到期
        if (nowElapsedMs - bucket.windowStartElapsedMs >= windowMs) {
            // 窗口到期，输出聚合结果
            buckets.remove(bucketKey)
            if (bucket.eventCount > 0) {
                if (!skipDebugLogs) {
                    logger?.d("Aggregation window expired for $bucketKey: ${bucket.eventCount} events")
                }
                emitAggregated(bucket.toAggregatedEvent(nowTimestampMs).toApmEvent())
            }
        }

        // The current event is swallowed, but an evicted or expired bucket may be emitted.
        return output ?: emptyList()
    }

    /**
     * 处理 ALERT 事件去重。
     * 首次出现正常上报，重复的出现增加首次事件的 count 字段。
     */
    private fun deduplicateAlert(event: ApmEvent): List<ApmEvent> {
        return when (val result = stackFingerprinter.check(event)) {
            is StackFingerprinter.DedupResult.New -> {
                // 首次出现，正常上报
                listOf(event)
            }
            is StackFingerprinter.DedupResult.Duplicate -> {
                // 重复事件，在 extras 中记录重复次数但不上报
                if (!skipDebugLogs) {
                    logger?.d("Deduplicated ${event.module}/${event.name}, count=${result.totalCount}")
                }
                emptyList()
            }
        }
    }

    companion object {
        /** 默认聚合窗口：5 分钟。 */
        private const val DEFAULT_WINDOW_MS = 300_000L

        /** Default maximum active bucket count. */
        private const val DEFAULT_MAX_BUCKETS = 128

        /** Default percentile reservoir size per numeric field. */
        private const val DEFAULT_MAX_SAMPLES_PER_FIELD = 256
    }
}

/**
 * 聚合桶：收集同一 module/name 的 METRIC 事件采样。
 */
private class AggregationBucket(
    /** 模块名。 */
    val module: String,
    /** 事件名。 */
    val name: String,
    /** 窗口起始时间。 */
    val windowStartTimestampMs: Long,
    /** Monotonic window origin used only for expiration decisions. */
    val windowStartElapsedMs: Long,
    /** Maximum samples retained per numeric field. */
    private val maxSamplesPerField: Int
) {
    /** Number of metric events containing at least one numeric field. */
    var eventCount: Int = 0
        private set

    /** Streaming numeric accumulators keyed by field name. */
    private val fields = LinkedHashMap<String, NumericAccumulator>()

    /**
     * 添加一个事件的数值字段作为采样。
     * 只提取可转为 Double 的字段值。
     */
    fun addSample(fields: Map<String, Any?>) {
        var added = false
        for ((key, value) in fields) {
            val numericValue = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            } ?: continue
            if (this.fields.size >= MAX_FIELDS_PER_BUCKET && key !in this.fields) {
                continue
            }
            this.fields.getOrPut(key) {
                NumericAccumulator(maxSamplesPerField.coerceAtLeast(1))
            }.add(numericValue)
            added = true
        }
        if (added) {
            eventCount++
        }
    }

    /**
     * 将桶内采样数据转换为聚合结果。
     */
    fun toAggregatedEvent(windowEndTimestampMs: Long): AggregatedEvent {
        val fieldStats = mutableMapOf<String, NumericStats>()
        for ((fieldName, accumulator) in fields) {
            fieldStats[fieldName] = accumulator.snapshot()
        }

        return AggregatedEvent(
            module = module,
            name = name,
            windowStartMs = windowStartTimestampMs,
            windowEndMs = windowEndTimestampMs,
            count = eventCount,
            fieldStats = fieldStats
        )
    }

    companion object {
        /** Hard bound for distinct numeric field names in one bucket. */
        private const val MAX_FIELDS_PER_BUCKET = 64
    }
}

/**
 * Streaming numeric summary with a bounded percentile reservoir.
 */
private class NumericAccumulator(private val reservoirSize: Int) {
    /** Bounded percentile sample reservoir. */
    private val reservoir = ArrayList<Double>(reservoirSize)

    /** Number of values observed. */
    private var count = 0

    /** Sum of all observed values. */
    private var sum = 0.0

    /** Minimum observed value. */
    private var min = Double.POSITIVE_INFINITY

    /** Maximum observed value. */
    private var max = Double.NEGATIVE_INFINITY

    /**
     * Adds one value to the streaming summary.
     *
     * @param value numeric value
     */
    fun add(value: Double) {
        count++
        sum += value
        min = kotlin.math.min(min, value)
        max = kotlin.math.max(max, value)
        if (reservoir.size < reservoirSize) {
            reservoir += value
            return
        }
        // Deterministic reservoir replacement keeps memory fixed and tests stable.
        val mixed = (count.toLong() * RESERVOIR_MULTIPLIER) xor (count.toLong() ushr RESERVOIR_SHIFT)
        val candidateIndex = ((mixed and Long.MAX_VALUE) % count).toInt()
        if (candidateIndex < reservoirSize) {
            reservoir[candidateIndex] = value
        }
    }

    /**
     * Creates an immutable statistics snapshot.
     *
     * @return current numeric summary
     */
    fun snapshot(): NumericStats {
        return NumericStats.fromSummary(
            samples = reservoir,
            min = min,
            max = max,
            sum = sum,
            sampleCount = count
        )
    }

    companion object {
        /** Deterministic mixing constant used by reservoir replacement. */
        private const val RESERVOIR_MULTIPLIER = 1_103_515_245L

        /** Bit shift used to mix sequential sample counts. */
        private const val RESERVOIR_SHIFT = 16
    }
}
