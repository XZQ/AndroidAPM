package com.apm.otel

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind

/**
 * APM 事件 → OpenTelemetry 桥接器。
 *
 * 将 APM 事件按类型路由到对应的 OTel Exporter：
 * - ALERT → [OtelSpanExporter]（Span）
 * - METRIC → [OtelMetricExporter]（Metric）
 * - FILE → LogRecord（日志）
 *
 * 所有事件同时可选输出为 LogRecord（当 [OtelConfig.exportLogs] = true）。
 *
 * 使用方式：
 * ```kotlin
 * val bridge = OtelEventBridge(OtelConfig(endpoint = "http://collector:4317"))
 * bridge.export(event)
 * ```
 */
class OtelEventBridge(
    /** OTel 配置。 */
    private val config: OtelConfig
) {

    /**
     * 导出单条 APM 事件到 OTel 管线。
     *
     * 根据事件类型和配置，路由到对应的转换器。
     *
     * @param event APM 事件
     * @return 导出结果，包含生成的 Span/Metric/Log 数据
     */
    fun export(event: ApmEvent): ExportResult {
        if (!config.enabled) return ExportResult(emptyList(), emptyList(), null)

        val spans = mutableListOf<Map<String, Any?>>()
        val metrics = mutableListOf<Map<String, Any?>>()
        var log: Map<String, Any?>? = null

        // 按类型路由
        when (event.kind) {
            ApmEventKind.ALERT -> {
                // ALERT 事件 → Span
                if (config.exportSpans) {
                    OtelSpanExporter.toSpanData(event)?.let { spans.add(it) }
                }
            }
            ApmEventKind.METRIC -> {
                // METRIC 事件 → Metric 数据点
                if (config.exportMetrics) {
                    metrics.addAll(OtelMetricExporter.toMetricData(event))
                }
            }
            ApmEventKind.FILE -> {
                // FILE 事件 → Log（带文件路径属性）
            }
        }

        // 所有事件可选导出为 Log
        if (config.exportLogs) {
            log = toLogData(event)
        }

        return ExportResult(spans, metrics, log)
    }

    /**
     * 批量导出多条事件。
     *
     * @param events APM 事件列表
     * @return 合并的导出结果
     */
    fun exportBatch(events: List<ApmEvent>): ExportResult {
        val allSpans = mutableListOf<Map<String, Any?>>()
        val allMetrics = mutableListOf<Map<String, Any?>>()
        // 日志取最后一条
        var lastLog: Map<String, Any?>? = null

        for (event in events) {
            val result = export(event)
            allSpans.addAll(result.spans)
            allMetrics.addAll(result.metrics)
            lastLog = result.log
        }

        return ExportResult(allSpans, allMetrics, lastLog)
    }

    /**
     * 将任意 APM 事件转换为 OTel LogRecord 兼容的 Map。
     */
    private fun toLogData(event: ApmEvent): Map<String, Any?> {
        val attributes = mutableMapOf<String, Any>()
        attributes["apm.module"] = event.module
        attributes["apm.name"] = event.name
        attributes["apm.kind"] = event.kind.name
        attributes["apm.severity"] = event.severity.name
        attributes["apm.priority"] = event.priority.name
        attributes["apm.process"] = event.processName
        attributes["apm.thread"] = event.threadName

        event.scene?.let { attributes["apm.scene"] = it }
        event.foreground?.let { attributes["app.foreground"] = it }

        for ((key, value) in event.fields) {
            if (value != null) attributes["field.$key"] = value
        }
        for ((key, value) in event.extras) {
            attributes["extras.$key"] = value
        }

        return mapOf(
            "body" to event.toLogBody(),
            "severityText" to event.severity.name,
            "epochMs" to event.timestamp,
            "attributes" to attributes
        )
    }

    /**
     * 导出结果。
     */
    data class ExportResult(
        /** 导出的 Span 数据列表。 */
        val spans: List<Map<String, Any?>>,
        /** 导出的 Metric 数据点列表。 */
        val metrics: List<Map<String, Any?>>,
        /** 导出的 LogRecord 数据（最后一条）。 */
        val log: Map<String, Any?>?
    )
}

/**
 * 将事件转换为日志文本。
 */
private fun ApmEvent.toLogBody(): String {
    val sb = StringBuilder()
    sb.append("[$module] $name")
    sb.append(" severity=$severity kind=$kind priority=$priority")
    if (fields.isNotEmpty()) {
        sb.append(" fields={")
        fields.entries.sortedBy { it.key }.forEach { (k, v) ->
            sb.append("$k=$v ")
        }
        sb.append("}")
    }
    return sb.toString()
}
