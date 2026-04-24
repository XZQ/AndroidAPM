package com.apm.trace

/**
 * Span 上下文，承载链路追踪中的关联信息。
 * 同一条 trace 下所有 span 共享相同的 traceId，
 * 通过 spanId / parentSpanId 构成树形调用关系。
 */
data class SpanContext(
    /** 唯一 trace ID，同一链路的所有 span 共享。 */
    val traceId: String,
    /** 当前 span 的唯一标识。 */
    val spanId: String,
    /** 父 span ID，根 span 时为 null。 */
    val parentSpanId: String? = null
)
