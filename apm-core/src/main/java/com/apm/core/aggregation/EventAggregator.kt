package com.apm.core.aggregation

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
    private val logger: ApmLogger? = null
) {
    /** 栈指纹去重器，用于 ALERT 类事件去重。 */
    private val stackFingerprinter = StackFingerprinter()

    /**
     * 活跃的聚合桶。
     * key = "${module}/${name}"，value = 该桶的采样数据。
     */
    private val buckets = LinkedHashMap<String, AggregationBucket>()

    /**
     * 处理一个事件，返回应该上报的事件列表。
     *
     * @param event 原始事件
     * @return 需要上报的事件列表（可能是原始事件、聚合后的事件、或空列表）
     */
    @Synchronized
    fun process(event: ApmEvent): List<ApmEvent> {
        if (!enabled) return listOf(event)

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
        if (buckets.isEmpty()) return emptyList()

        val results = mutableListOf<ApmEvent>()
        val now = System.currentTimeMillis()

        for ((key, bucket) in buckets) {
            if (bucket.samples.isNotEmpty()) {
                val aggregated = bucket.toAggregatedEvent(key, now)
                results.add(aggregated.toApmEvent())
                logger?.d("Flushed aggregation bucket $key: ${bucket.samples.size} events")
            }
        }

        buckets.clear()
        return results
    }

    /**
     * 处理 METRIC 事件聚合。
     * 如果窗口未满，吞入事件返回空列表；如果窗口到期，输出聚合结果。
     */
    private fun aggregateMetric(event: ApmEvent): List<ApmEvent> {
        val bucketKey = "${event.module}/${event.name}"
        val now = System.currentTimeMillis()

        // 获取或创建聚合桶
        val bucket = buckets.getOrPut(bucketKey) {
            AggregationBucket(
                module = event.module,
                name = event.name,
                windowStartMs = now
            )
        }

        // 将事件的数值字段加入桶
        bucket.addSample(event.fields, now)

        // 检查窗口是否到期
        if (now - bucket.windowStartMs >= windowMs) {
            // 窗口到期，输出聚合结果
            buckets.remove(bucketKey)
            if (bucket.samples.isNotEmpty()) {
                val aggregated = bucket.toAggregatedEvent(bucketKey, now)
                logger?.d("Aggregation window expired for $bucketKey: ${bucket.samples.size} events")
                return listOf(aggregated.toApmEvent())
            }
        }

        // 窗口未满，事件被聚合吞掉，不上报
        return emptyList()
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
                logger?.d("Deduplicated ${event.module}/${event.name}, count=${result.totalCount}")
                emptyList()
            }
        }
    }

    companion object {
        /** 默认聚合窗口：5 分钟。 */
        private const val DEFAULT_WINDOW_MS = 300_000L
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
    var windowStartMs: Long
) {
    /**
     * 采样数据列表。
     * 每个采样是一个 Map<字段名, 字段值>。
     */
    val samples = mutableListOf<Map<String, Double>>()

    /**
     * 添加一个事件的数值字段作为采样。
     * 只提取可转为 Double 的字段值。
     */
    fun addSample(fields: Map<String, Any?>, timestamp: Long) {
        val numericFields = mutableMapOf<String, Double>()
        for ((key, value) in fields) {
            when (value) {
                is Number -> numericFields[key] = value.toDouble()
                is String -> {
                    // 尝试将字符串解析为数字
                    value.toDoubleOrNull()?.let { numericFields[key] = it }
                }
            }
        }
        if (numericFields.isNotEmpty()) {
            samples.add(numericFields)
        }
    }

    /**
     * 将桶内采样数据转换为聚合结果。
     */
    fun toAggregatedEvent(bucketKey: String, now: Long): AggregatedEvent {
        // 收集所有出现过的字段名
        val allFieldNames = mutableSetOf<String>()
        for (sample in samples) {
            allFieldNames.addAll(sample.keys)
        }

        // 对每个字段计算统计摘要
        val fieldStats = mutableMapOf<String, NumericStats>()
        for (fieldName in allFieldNames) {
            val values = samples.mapNotNull { it[fieldName] }
            if (values.isNotEmpty()) {
                fieldStats[fieldName] = NumericStats.fromSortedSamples(values)
            }
        }

        return AggregatedEvent(
            module = module,
            name = name,
            windowStartMs = windowStartMs,
            windowEndMs = now,
            count = samples.size,
            fieldStats = fieldStats
        )
    }
}
