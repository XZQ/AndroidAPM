package com.apm.otel

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity

/**
 * APM 事件 → OpenTelemetry Span 转换器。
 *
 * 将 ALERT 类型的 APM 事件（崩溃、ANR、泄漏等）转换为 OTel SpanData。
 * METRIC 事件不适合映射为 Span，由 [OtelMetricExporter] 处理。
 *
 * 映射规则：
 * - module → Span name
 * - name → Span description
 * - timestamp → Span start/end time
 * - severity ≥ ERROR → Span Status ERROR
 * - fields → Span attributes
 * - extras → Span attributes（前缀 extras.）
 *
 * 注意：此类依赖 OTel SDK（compileOnly），
 * 运行时需要宿主 App 提供 OTel SDK 实现。
 */
object OtelSpanExporter {

    /**
     * 将 APM 事件转换为 OTel 兼容的 Span 数据 Map。
     *
     * 由于 OTel SDK 是 compileOnly 依赖，这里输出标准化的 Map 结构，
     * 宿主 App 的 OTel 集成层可据此构建 SpanData。
     *
     * @param event APM 事件
     * @return OTel Span 兼容的 Map 数据，或 null（非 ALERT 类型）
     */
    fun toSpanData(event: ApmEvent): Map<String, Any?>? {
        // 只有 ALERT 类型事件映射为 Span
        if (event.kind != ApmEventKind.ALERT) return null

        val attributes = mutableMapOf<String, Any>()
        // 事件基础信息作为属性
        attributes["apm.module"] = event.module
        attributes["apm.name"] = event.name
        attributes["apm.severity"] = event.severity.name
        attributes["apm.priority"] = event.priority.name
        attributes["apm.process"] = event.processName
        attributes["apm.thread"] = event.threadName

        // 可选字段
        event.scene?.let { attributes["apm.scene"] = it }
        event.foreground?.let { attributes["app.foreground"] = it }

        // 业务指标字段
        for ((key, value) in event.fields) {
            if (value != null) {
                attributes["apm.field.$key"] = value
            }
        }

        // 附加键值对
        for ((key, value) in event.extras) {
            attributes["extras.$key"] = value
        }

        // 全局上下文
        for ((key, value) in event.globalContext) {
            attributes["context.$key"] = value
        }

        // Span 状态
        val status = when {
            event.severity >= ApmSeverity.ERROR -> "ERROR"
            event.severity == ApmSeverity.WARN -> "UNSET"
            else -> "OK"
        }

        return mapOf(
            "traceId" to generateTraceId(event),
            "spanId" to generateSpanId(event),
            "name" to event.module,
            "description" to event.name,
            "kind" to "INTERNAL",
            "startEpochMs" to event.timestamp,
            "endEpochMs" to event.timestamp,
            "status" to status,
            "attributes" to attributes
        )
    }

    /**
     * 根据事件生成稳定的 traceId。
     * 同一 module + timestamp 组合生成相同的 traceId。
     */
    private fun generateTraceId(event: ApmEvent): String {
        val seed = "${event.module}:${event.timestamp}".hashCode().toLong()
        val hi = seed xor (event.timestamp ushr 32)
        val lo = event.timestamp
        return "%016x%016x".format(hi, lo)
    }

    /**
     * 根据事件生成唯一的 spanId。
     */
    private fun generateSpanId(event: ApmEvent): String {
        val seed = "${event.name}:${event.timestamp}:${event.threadName}".hashCode().toLong()
        return "%016x".format(seed xor System.nanoTime())
    }
}
