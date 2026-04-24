package com.apm.core.aggregation

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity

/**
 * 聚合后的事件统计结果。
 *
 * 高频 METRIC 事件在滑动窗口内聚合后输出一条 [AggregatedEvent]，
 * 包含 P50/P90/P99 分位数、最小/最大值、计数等信息，
 * 大幅减少上报量（典型场景减少 90%+）。
 *
 * 聚合后的事件通过 [toApmEvent] 转换为标准 [ApmEvent] 进入正常上报管线。
 */
data class AggregatedEvent(
    /** 原始事件的模块名。 */
    val module: String,
    /** 原始事件名。 */
    val name: String,
    /** 聚合窗口起始时间戳（毫秒）。 */
    val windowStartMs: Long,
    /** 聚合窗口结束时间戳（毫秒）。 */
    val windowEndMs: Long,
    /** 窗口内的事件总数。 */
    val count: Int,
    /** 被聚合的数值字段统计。key = 原始 fields 中的键名。 */
    val fieldStats: Map<String, NumericStats>
)

/**
 * 单个数值字段的统计摘要。
 *
 * 记录该字段在聚合窗口内的分布信息。
 */
data class NumericStats(
    /** P50（中位数）。 */
    val p50: Double,
    /** P90。 */
    val p90: Double,
    /** P99。 */
    val p99: Double,
    /** 最小值。 */
    val min: Double,
    /** 最大值。 */
    val max: Double,
    /** 总和。 */
    val sum: Double,
    /** 参与统计的样本数。 */
    val sampleCount: Int
) {
    companion object {
        /** 从排序后的样本列表计算统计摘要。 */
        fun fromSortedSamples(samples: List<Double>): NumericStats {
            if (samples.isEmpty()) {
                return NumericStats(p50 = 0.0, p90 = 0.0, p99 = 0.0, min = 0.0, max = 0.0, sum = 0.0, sampleCount = 0)
            }
            val sorted = samples.sorted()
            return NumericStats(
                p50 = percentile(sorted, 0.50),
                p90 = percentile(sorted, 0.90),
                p99 = percentile(sorted, 0.99),
                min = sorted.first(),
                max = sorted.last(),
                sum = sorted.sum(),
                sampleCount = sorted.size
            )
        }

        /** 计算百分位数（线性插值）。 */
        private fun percentile(sorted: List<Double>, p: Double): Double {
            if (sorted.size == 1) return sorted[0]
            val index = p * (sorted.size - 1)
            val lower = index.toInt()
            val upper = lower + 1
            if (upper >= sorted.size) return sorted.last()
            val fraction = index - lower
            return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
        }
    }
}

/**
 * 将聚合结果转换为标准 [ApmEvent] 以便进入正常上报管线。
 */
fun AggregatedEvent.toApmEvent(): ApmEvent {
    val fields = mutableMapOf<String, Any?>(
        "window_start_ms" to windowStartMs,
        "window_end_ms" to windowEndMs,
        "count" to count
    )
    // 展开每个字段的统计指标
    for ((fieldName, stats) in fieldStats) {
        fields["${fieldName}_p50"] = "%.2f".format(stats.p50)
        fields["${fieldName}_p90"] = "%.2f".format(stats.p90)
        fields["${fieldName}_p99"] = "%.2f".format(stats.p99)
        fields["${fieldName}_min"] = "%.2f".format(stats.min)
        fields["${fieldName}_max"] = "%.2f".format(stats.max)
        fields["${fieldName}_sum"] = "%.2f".format(stats.sum)
    }
    return ApmEvent(
        module = module,
        name = "${name}_aggregated",
        kind = ApmEventKind.METRIC,
        severity = ApmSeverity.INFO,
        timestamp = windowEndMs,
        fields = fields
    )
}
