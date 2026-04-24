package com.apm.trace

import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity

/**
 * APM Span 表示一个追踪单元。
 *
 * 记录一段操作的名称、耗时、层级关系和自定义属性。
 * Span 结束时自动通过 [Apm.emit] 上报到 APM 管线。
 *
 * 使用方式：
 * ```kotlin
 * val span = ApmTrace.span("payment_checkout")
 *     .setAttribute("amount", "99.9")
 *     .start()
 * // ... do work ...
 * span.end()
 * ```
 *
 * 支持嵌套：
 * ```kotlin
 * val child = ApmTrace.span("api_call")
 *     .setParent(span)
 *     .start()
 * child.end()
 * ```
 */
class ApmSpan internal constructor(
    /** Span 操作名称。 */
    val name: String,
    /** 所属 Trace 配置。 */
    private val config: TraceConfig
) {
    /** Span 上下文（traceId、spanId、parentSpanId）。 */
    var context: SpanContext = SpanContext(
        traceId = "",
        spanId = "",
        parentSpanId = null
    )
        private set

    /** Span 开始时间戳（毫秒）。未 start 时为 -1。 */
    var startTimestampMs: Long = -1
        private set

    /** Span 结束时间戳（毫秒）。未 end 时为 -1。 */
    var endTimestampMs: Long = -1
        private set

    /** Span 是否已完成。 */
    val isFinished: Boolean
        get() = endTimestampMs > 0

    /** Span 持续时间（毫秒）。未结束时返回 -1。 */
    val durationMs: Long
        get() = if (isFinished) endTimestampMs - startTimestampMs else -1

    /** 自定义属性。 */
    private val attributes = LinkedHashMap<String, String>()

    /** Span 状态。 */
    private var status: SpanStatus = SpanStatus.OK

    /**
     * 设置父 Span，用于构建嵌套调用关系。
     * 必须在 [start] 之前调用。
     *
     * @param parent 父 Span
     * @return this，支持链式调用
     */
    fun setParent(parent: ApmSpan): ApmSpan {
        this.parentSpan = parent
        return this
    }

    /**
     * 设置自定义属性。
     * 属性上限由 [TraceConfig.maxAttributes] 控制。
     *
     * @param key 属性键
     * @param value 属性值
     * @return this，支持链式调用
     */
    fun setAttribute(key: String, value: String): ApmSpan {
        // 超出上限时移除最旧的属性
        if (attributes.size >= config.maxAttributes && !attributes.containsKey(key)) {
            val oldest = attributes.keys.first()
            attributes.remove(oldest)
        }
        attributes[key] = value
        return this
    }

    /**
     * 设置 Span 状态为错误。
     *
     * @param errorMessage 错误描述
     * @return this，支持链式调用
     */
    fun setError(errorMessage: String): ApmSpan {
        status = SpanStatus.ERROR
        attributes["error"] = errorMessage
        return this
    }

    /**
     * 开始 Span，记录开始时间戳并生成上下文。
     *
     * @return this，支持链式调用
     */
    fun start(): ApmSpan {
        if (!config.enabled) return this
        startTimestampMs = System.currentTimeMillis()

        // 构建 Span 上下文
        val parentRef = parentSpan
        val spanId = IdGenerator.generateSpanId()
        val traceId = if (parentRef != null && parentRef.context.traceId.isNotEmpty()) {
            parentRef.context.traceId
        } else {
            IdGenerator.generateTraceId()
        }
        val parentSpanId = parentRef?.context?.spanId
        context = SpanContext(traceId = traceId, spanId = spanId, parentSpanId = parentSpanId)

        // 检查最大持续时间限制
        if (config.maxSpanDurationMs > 0) {
            maxSpanTimeout = startTimestampMs + config.maxSpanDurationMs
        }
        return this
    }

    /**
     * 结束 Span，记录结束时间戳并上报。
     * 多次调用安全（幂等）。
     */
    fun end() {
        if (!config.enabled || isFinished) return
        endTimestampMs = System.currentTimeMillis()

        // 超时检查
        if (maxSpanTimeout > 0 && endTimestampMs > maxSpanTimeout) {
            attributes["timeout"] = "true"
        }

        // 自动上报到 APM 管线
        if (config.autoReport) {
            report()
        }
    }

    /**
     * 将 Span 数据上报到 APM 管线。
     */
    private fun report() {
        val fields = mutableMapOf<String, Any?>(
            "traceId" to context.traceId,
            "spanId" to context.spanId,
            "duration_ms" to durationMs,
            "status" to status.name
        )
        // 父 Span ID
        context.parentSpanId?.let { fields["parentSpanId"] = it }
        // 合并自定义属性
        for ((key, value) in attributes) {
            fields["attr_$key"] = value
        }

        val severity = when (status) {
            SpanStatus.OK -> ApmSeverity.DEBUG
            SpanStatus.ERROR -> ApmSeverity.WARN
        }

        Apm.emit(
            module = config.reportModule,
            name = name,
            kind = ApmEventKind.METRIC,
            severity = severity,
            priority = ApmPriority.NORMAL,
            fields = fields
        )
    }

    /** 父 Span 引用。 */
    private var parentSpan: ApmSpan? = null

    /** 最大超时时间戳。 */
    private var maxSpanTimeout: Long = 0
}

/**
 * Span 状态。
 */
enum class SpanStatus {
    /** 正常完成。 */
    OK,
    /** 错误状态。 */
    ERROR
}
