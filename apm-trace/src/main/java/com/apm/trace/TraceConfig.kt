package com.apm.trace

/**
 * Trace 模块配置。
 * 控制手动埋点模块的全局行为，通过 [ApmTrace.config] 设置。
 */
data class TraceConfig(
    /** 是否启用 Trace 模块。禁用后 span 的 end 仍可安全调用，但不会执行上报。 */
    val enabled: Boolean = true,
    /** 单个 Span 最大持续时间（毫秒），超过后自动结束并标记超时。0 表示不限制。 */
    val maxSpanDurationMs: Long = 0,
    /** 是否在 Span 结束时自动上报到 APM 管线。 */
    val autoReport: Boolean = true,
    /** Span 上报的模块名。 */
    val reportModule: String = "trace",
    /** 自定义属性上限，超出时丢弃最旧的属性。 */
    val maxAttributes: Int = 32
)
