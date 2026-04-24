package com.apm.otel

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind

/**
 * APM 事件 → OpenTelemetry Metric 转换器。
 *
 * 将 METRIC 类型的 APM 事件转换为 OTel MetricData 兼容的 Map 结构。
 * 支持 Gauge（单值）和 Histogram（分布）两种数据类型。
 *
 * 映射规则：
 * - module.name → Metric name（如 "apm.memory.snapshot"）
 * - fields 中的数值 → Gauge 数据点
 * - severity → Metric severity label
 * - timestamp → Metric timestamp
 *
 * 注意：OTel SDK 为 compileOnly 依赖，宿主 App 需自行提供实现。
 */
object OtelMetricExporter {

    /**
     * 将 APM 事件转换为 OTel Metric 数据点列表。
     *
     * fields 中的每个数值类型字段生成一个独立的 Metric 数据点。
     *
     * @param event APM 事件
     * @return Metric 数据点列表，或空列表（非 METRIC 类型或无数值字段）
     */
    fun toMetricData(event: ApmEvent): List<Map<String, Any?>> {
        // 只有 METRIC 类型事件映射为 Metric
        if (event.kind != ApmEventKind.METRIC) return emptyList()

        val metricName = "apm.${event.module}.${event.name}"
        val baseAttributes = mutableMapOf<String, String>()
        baseAttributes["apm.severity"] = event.severity.name
        baseAttributes["apm.priority"] = event.priority.name
        event.scene?.let { baseAttributes["apm.scene"] = it }
        event.foreground?.let { baseAttributes["app.foreground"] = it.toString() }

        val dataPoints = mutableListOf<Map<String, Any?>>()

        // 遍历 fields 中的数值，每个数值生成一个 Gauge 数据点
        for ((key, value) in event.fields) {
            val numericValue = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
            if (numericValue != null) {
                val attrs = baseAttributes.toMutableMap()
                attrs["apm.field"] = key
                dataPoints.add(mapOf(
                    "metricName" to "$metricName.$key",
                    "type" to "GAUGE",
                    "value" to numericValue,
                    "epochMs" to event.timestamp,
                    "attributes" to attrs
                ))
            }
        }

        return dataPoints
    }

    /**
     * 批量转换多个事件为 Metric 数据点。
     *
     * @param events APM 事件列表
     * @return 所有 Metric 数据点的扁平列表
     */
    fun toMetricDataBatch(events: List<ApmEvent>): List<Map<String, Any?>> {
        return events.flatMap { toMetricData(it) }
    }
}
