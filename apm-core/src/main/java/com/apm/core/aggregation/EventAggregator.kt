package com.apm.core.aggregation

import com.apm.core.ApmClock
import com.apm.core.canSkipDebugLogs
import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.core.ApmLogger
import com.apm.core.ApmEventSizeEstimator
import com.apm.core.snapshotEvent
import com.apm.core.privacy.PiiSanitizer
import com.apm.model.ApmOccurrenceContext
import com.apm.model.ApmPriority
import kotlin.random.Random

/**
 * 客户端事件聚合器。
 *
 * 在滑动窗口内对高频 METRIC 事件进行聚合：
 * - 数值字段计算 P50/P90/P99/min/max/count
 * - 聚合后输出一条 [AggregatedEvent]，大幅减少上报量
 *
 * 普通 ALERT 通过 [StackFingerprinter] 分组并输出重复次数增量；关键 Crash/ANR 另走同步通道。
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
    /** Reuses the sanitizer's bounded, name-only classifier without executing text rules. */
    private val fieldClassifier = PiiSanitizer(emptyList())

    /** 栈指纹去重器，用于 ALERT 类事件去重。 */
    private val stackFingerprinter = StackFingerprinter()

    /**
     * 高频路径 debug 日志守卫：内置 logger 关闭 debug 输出时跳过模板构串
     * （此时 d() 是可证明的空操作）；自定义 logger 恒为 false，行为不变。
     */
    private val skipDebugLogs = canSkipDebugLogs(logger)

    /**
     * 活跃的聚合桶。
     * Keys include occurrence identity and dimensions; the existing bucket limit bounds cardinality.
     */
    private val buckets = LinkedHashMap<AggregationKey, AggregationBucket>()

    /** Bounded ordinary-alert windows; the first event is delivered immediately. */
    private val alertBuckets = LinkedHashMap<AlertKey, AlertBucket>()

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
        if (buckets.isEmpty() && alertBuckets.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<ApmEvent>()
        val nowTimestampMs = ApmClock.wallTimeMillis()

        for ((key, bucket) in buckets) {
            if (bucket.eventCount > 0) {
                results.add(bucket.toEvent(nowTimestampMs))
                logger?.d("Flushed aggregation bucket: ${bucket.eventCount} events")
            }
        }

        buckets.clear()
        alertBuckets.values.forEach { it.deltaEvent()?.let(results::add) }
        alertBuckets.clear()
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
        val results = flushExpiredAlerts(nowElapsedMs)
        val nowTimestampMs = ApmClock.wallTimeMillis()
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val (key, bucket) = iterator.next()
            if (nowElapsedMs - bucket.windowStartElapsedMs < windowMs) {
                continue
            }
            iterator.remove()
            if (bucket.eventCount > 0) {
                results += bucket.toEvent(nowTimestampMs)
            }
        }
        return results
    }

    /**
     * 处理 METRIC 事件聚合。
     * 如果窗口未满，吞入事件返回空列表；如果窗口到期，输出聚合结果。
     */
    private fun aggregateMetric(event: ApmEvent): List<ApmEvent> {
        // Large/high-cardinality dimensions and non-numeric events remain ordinary telemetry.
        // This prevents both silent loss of text-only metrics and unbounded retained templates.
        val template = snapshotEvent(event)
        if (ApmEventSizeEstimator.estimate(template) > MAX_AGGREGATION_EVENT_BYTES ||
            template.fields.size > MAX_AGGREGATION_FIELDS
        ) return listOf(event)
        val numericFields = template.fields.filter { (key, value) ->
            !fieldClassifier.isSensitiveFieldName(key) && numericMetricValue(value) != null
        }.keys
        if (numericFields.isEmpty()) return listOf(event)
        val dimensions = template.fields.filterKeys { it !in numericFields }
        // Numeric-only streams allocate no generated-name list; only matching dimension suffixes
        // need a prefix lookup to prove that an output statistic would collide.
        if (dimensions.keys.any { key ->
                key in WINDOW_FIELD_NAMES || STAT_SUFFIXES.any { suffix ->
                    key.endsWith(suffix) && key.dropLast(suffix.length) in numericFields
                }
            }) return listOf(event)
        val bucketKey = AggregationKey(template, dimensions, numericFields)
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
                        emitAggregated(eldest.value.toEvent(nowTimestampMs))
                    }
                }
            }
            bucket = AggregationBucket(
                template = template.copy(fields = dimensions).let { copy ->
                    template.occurrence?.let(copy::withOccurrenceContext) ?: copy
                },
                windowStartTimestampMs = nowTimestampMs,
                windowStartElapsedMs = nowElapsedMs,
                maxSamplesPerField = maxSamplesPerField
            )
            buckets[bucketKey] = bucket
        }

        // 将事件的数值字段加入桶
        bucket.addSample(template.fields, numericFields)

        // 检查窗口是否到期
        if (nowElapsedMs - bucket.windowStartElapsedMs >= windowMs) {
            // 窗口到期，输出聚合结果
            buckets.remove(bucketKey)
            if (bucket.eventCount > 0) {
                if (!skipDebugLogs) {
                    logger?.d("Aggregation window expired: ${bucket.eventCount} events")
                }
                emitAggregated(bucket.toEvent(nowTimestampMs))
            }
        }

        // The current event is swallowed, but an evicted or expired bucket may be emitted.
        return output ?: emptyList()
    }

    /** Delivers first occurrence immediately and retains only an unsent duplicate delta. */
    private fun deduplicateAlert(event: ApmEvent): List<ApmEvent> {
        val template = snapshotEvent(event)
        if (ApmEventSizeEstimator.estimate(template) > MAX_AGGREGATION_EVENT_BYTES ||
            template.fields.keys.any { it in WINDOW_FIELD_NAMES } ||
            template.extras.keys.any { it in ALERT_RESERVED_EXTRAS }
        ) return listOf(event)
        val fingerprint = stackFingerprinter.fingerprintOf(template) ?: return listOf(event)
        val dimensions = template.fields.filterKeys { it !in StackFingerprinter.STACK_FIELDS }
        val key = AlertKey(AggregationKey(template, dimensions, emptySet()), fingerprint)
        val now = ApmClock.monotonicTimeMillis()
        val output = flushExpiredAlerts(now)
        val existing = alertBuckets[key]
        if (existing != null) {
            // Flush before saturation so no represented occurrence count is silently lost.
            if (existing.duplicateCount == Int.MAX_VALUE) existing.deltaEvent()?.let(output::add)
            existing.addDuplicate(template.timestamp)
            return output
        }
        if (alertBuckets.size >= maxBuckets.coerceAtLeast(1)) {
            val oldest = alertBuckets.entries.first()
            alertBuckets.remove(oldest.key)
            oldest.value.deltaEvent()?.let(output::add)
        }
        alertBuckets[key] = AlertBucket(template, now)
        output += event
        return output
    }

    /** Fixed windows expire even under continuous duplicates, with monotonic time only. */
    private fun flushExpiredAlerts(nowElapsedMs: Long): MutableList<ApmEvent> {
        val output = mutableListOf<ApmEvent>()
        val iterator = alertBuckets.values.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next()
            if (nowElapsedMs - bucket.startElapsedMs < windowMs) continue
            iterator.remove()
            bucket.deltaEvent()?.let(output::add)
        }
        return output
    }

    /** Alert equality includes release/process/context and non-stack fields, not only call frames. */
    private data class AlertKey(
        /** Frozen occurrence and complete non-stack dimensions. */ val dimensions: AggregationKey,
        /** Canonical bounded stack and exception identity. */ val fingerprint: String
    )

    /** Holds a bounded first-event template and the number of duplicates not yet delivered. */
    private class AlertBucket(
        /** Sanitized immutable first occurrence, including its original eventId. */ val template: ApmEvent,
        /** Fixed monotonic window origin. */ val startElapsedMs: Long
    ) {
        /** Delta excludes the already emitted first event and every previously emitted summary. */
        var duplicateCount = 0
            private set
        /** Earliest duplicate wall timestamp in the current delta. */
        private var firstDuplicateMs = Long.MAX_VALUE
        /** Latest duplicate wall timestamp in the current delta. */
        private var lastDuplicateMs = Long.MIN_VALUE

        /** Adds one unsent occurrence without replacing the already delivered first row. */
        fun addDuplicate(timestampMs: Long) {
            duplicateCount++
            firstDuplicateMs = minOf(firstDuplicateMs, timestampMs)
            lastDuplicateMs = maxOf(lastDuplicateMs, timestampMs)
        }

        /** Creates a fresh immutable eventId for one delta; zero duplicates produce no event. */
        fun deltaEvent(): ApmEvent? {
            if (duplicateCount == 0) return null
            val result = template.copy(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = lastDuplicateMs,
                fields = template.fields + mapOf(
                    "count" to duplicateCount, "window_start_ms" to firstDuplicateMs,
                    "window_end_ms" to lastDuplicateMs
                ),
                extras = template.extras + mapOf(
                    ALERT_AGGREGATION_KIND to "duplicate_delta", ALERT_SOURCE_EVENT_ID to template.eventId
                )
            )
            duplicateCount = 0
            firstDuplicateMs = Long.MAX_VALUE
            lastDuplicateMs = Long.MIN_VALUE
            return template.occurrence?.let(result::withOccurrenceContext) ?: result
        }
    }

    companion object {
        /** Describes unsent duplicate count rather than a cumulative update to an existing row. */
        private const val ALERT_AGGREGATION_KIND = "aggregation.kind"
        /** Immutable first-event identity retained as a diagnostic link. */
        private const val ALERT_SOURCE_EVENT_ID = "aggregation.source_event_id"
        /** Host-owned markers must not be overwritten by generated summaries. */
        private val ALERT_RESERVED_EXTRAS = setOf(ALERT_AGGREGATION_KIND, ALERT_SOURCE_EVENT_ID)

        /** 默认聚合窗口：5 分钟。 */
        private const val DEFAULT_WINDOW_MS = 300_000L

        /** Default maximum active bucket count. */
        private const val DEFAULT_MAX_BUCKETS = 128

        /** Default percentile reservoir size per numeric field. */
        private const val DEFAULT_MAX_SAMPLES_PER_FIELD = 256

        /** Per-template retained-byte bound in addition to the public bucket/sample bounds. */
        private const val MAX_AGGREGATION_EVENT_BYTES = 16 * 1024L

        /** Events beyond the numeric accumulator bound bypass aggregation intact. */
        private const val MAX_AGGREGATION_FIELDS = 64

        /** Generated suffixes that must not overwrite a non-numeric dimension. */
        private val STAT_SUFFIXES = listOf("_p50", "_p90", "_p99", "_min", "_max", "_sum", "_sample_count")

        /** Fixed fields describing an aggregation window. */
        private val WINDOW_FIELD_NAMES = setOf("count", "window_start_ms", "window_end_ms")
    }
}

/** Immutable grouping dimensions exclude only event identity, timestamp and numeric sample values. */
private data class AggregationKey(
    /** Module and event name are separate fields to avoid slash collisions. */
    val module: String,
    /** Original metric name. */
    val name: String,
    /** Original severity. */
    val severity: ApmSeverity,
    /** Original delivery priority. */
    val priority: ApmPriority,
    /** Original process. */
    val processName: String,
    /** Original emitting thread. */
    val threadName: String,
    /** Original scene. */
    val scene: String?,
    /** Original foreground state. */
    val foreground: Boolean?,
    /** Frozen business context. */
    val globalContext: Map<String, String>,
    /** Frozen extras. */
    val extras: Map<String, String>,
    /** Non-numeric dimensions. */
    val dimensions: Map<String, Any?>,
    /** Exact field schema prevents incompatible sample populations from merging. */
    val numericFields: Set<String>,
    /** Occurrence identity includes native frames and is never replaced by the flushing process. */
    val occurrence: ApmOccurrenceContext?
) {
    /** Builds a key from a frozen event and its numeric schema. */
    constructor(event: ApmEvent, dimensions: Map<String, Any?>, numericFields: Set<String>) : this(
        event.module, event.name, event.severity, event.priority, event.processName, event.threadName,
        event.scene, event.foreground, event.globalContext, event.extras, dimensions,
        numericFields.toSet(), event.occurrence
    )
}

/** Finite numeric values only; NaN/infinity and text-only records retain the ordinary event path. */
private fun numericMetricValue(value: Any?): Double? = when (value) {
    is Number -> value.toDouble().takeIf(Double::isFinite)
    else -> null
}

/**
 * 聚合桶：收集同一 module/name 的 METRIC 事件采样。
 */
private class AggregationBucket(
    /** Frozen occurrence and dimension template, with numeric sample values removed. */
    private val template: ApmEvent,
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
     * 只提取已通过字段名保护的显式 Number；数字文本保留为维度。
     */
    fun addSample(fields: Map<String, Any?>, numericFields: Set<String>) {
        var added = false
        for (key in numericFields) {
            val value = fields[key]
            val numericValue = numericMetricValue(value) ?: continue
            if (this.fields.size >= MAX_FIELDS_PER_BUCKET && key !in this.fields) {
                continue
            }
            this.fields.getOrPut(key) {
                NumericAccumulator(maxSamplesPerField.coerceAtLeast(1))
            }.add(numericValue)
            added = true
        }
        if (added) {
            if (eventCount < Int.MAX_VALUE) eventCount++
        }
    }

    /**
     * 将桶内采样数据转换为聚合结果。
     */
    fun toEvent(windowEndTimestampMs: Long): ApmEvent {
        val fieldStats = mutableMapOf<String, NumericStats>()
        for ((fieldName, accumulator) in fields) {
            fieldStats[fieldName] = accumulator.snapshot()
        }

        val aggregate = AggregatedEvent(
            module = template.module,
            name = template.name,
            windowStartMs = windowStartTimestampMs,
            windowEndMs = windowEndTimestampMs,
            count = eventCount,
            fieldStats = fieldStats
        ).toApmEvent()
        val result = aggregate.copy(
            severity = template.severity,
            priority = template.priority,
            processName = template.processName,
            threadName = template.threadName,
            scene = template.scene,
            foreground = template.foreground,
            globalContext = template.globalContext,
            extras = template.extras,
            fields = template.fields + aggregate.fields
        )
        return template.occurrence?.let(result::withOccurrenceContext) ?: result
    }

    companion object {
        /** Hard bound for distinct numeric field names in one bucket. */
        private const val MAX_FIELDS_PER_BUCKET = 64
    }
}

/**
 * Streaming numeric summary with a bounded percentile reservoir.
 */
internal class NumericAccumulator(
    /** Maximum retained percentile sample count. */
    private val reservoirSize: Int,
    /** Uniform source; tests inject a fixed seed without making production input-order predictable. */
    private val random: Random = Random.Default
) {
    /** Bounded percentile sample reservoir. */
    private val reservoir = ArrayList<Double>(reservoirSize)

    /** Number of values observed. */
    private var count = 0L

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
        if (count < Long.MAX_VALUE) count++
        sum += value
        min = kotlin.math.min(min, value)
        max = kotlin.math.max(max, value)
        if (reservoir.size < reservoirSize) {
            reservoir += value
            return
        }
        // Algorithm R: each of the n observations has k/n probability of surviving in k slots.
        // Random.nextLong(bound) avoids modulo bias and the old count-multiple degeneracy.
        val candidateIndex = random.nextLong(count)
        if (candidateIndex < reservoirSize.toLong()) {
            reservoir[candidateIndex.toInt()] = value
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
            // The public Int counter saturates while sampling continues with its Long population.
            sampleCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }
}
