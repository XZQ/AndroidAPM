package com.apm.otel

/**
 * OpenTelemetry Exporter 配置。
 *
 * 控制如何将 APM 事件桥接到 OpenTelemetry 管线。
 * 所有 OTel 依赖均为 compileOnly，宿主 App 需自行引入 OTel SDK。
 */
data class OtelConfig(
    /** 是否启用 OTel 桥接。 */
    val enabled: Boolean = true,
    /** OTel Service Name，用于 Resource 标识。 */
    val serviceName: String = "android-apm",
    /** OTel Endpoint（如 OTEL Collector 的 OTLP gRPC 地址）。 */
    val endpoint: String = "",
    /** 自定义 Resource 属性，附加到所有导出的 Span/Metric/Log。 */
    val resourceAttributes: Map<String, String> = emptyMap(),
    /** 是否导出 Span 事件（ALERT 类型的事件映射为 OTel Span）。 */
    val exportSpans: Boolean = true,
    /** 是否导出 Metric 事件（METRIC 类型映射为 OTel Gauge/Histogram）。 */
    val exportMetrics: Boolean = true,
    /** 是否导出 Log 事件（所有事件映射为 OTel LogRecord）。 */
    val exportLogs: Boolean = true
)
