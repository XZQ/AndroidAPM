package com.apm.trace

/**
 * APM Trace 入口。
 *
 * 提供手动埋点 Span API，方便业务标记关键路径。
 * Span 结束后自动通过 APM 管线上报。
 *
 * 使用方式：
 * ```kotlin
 * // 简单用法
 * val span = ApmTrace.span("payment_checkout")
 *     .setAttribute("amount", "99.9")
 *     .start()
 * // ... do work ...
 * span.end()
 *
 * // 嵌套 Span
 * val parent = ApmTrace.span("order_create").start()
 * val child = ApmTrace.span("db_insert").setParent(parent).start()
 * child.end()
 * parent.end()
 *
 * // 自定义配置
 * ApmTrace.config = TraceConfig(maxSpanDurationMs = 30_000)
 * ```
 */
object ApmTrace {

    /** 全局 Trace 配置。 */
    @Volatile
    var config: TraceConfig = TraceConfig()

    /**
     * 创建一个 Span Builder。
     *
     * @param name Span 操作名称，如 "payment_checkout"、"db_query"
     * @return Span 实例（未启动），可链式调用 setAttribute/setParent/start
     */
    fun span(name: String): ApmSpan {
        return ApmSpan(name, config)
    }

    /**
     * 创建并立即启动一个 Span（快捷方法）。
     *
     * @param name Span 操作名称
     * @return 已启动的 Span 实例
     */
    fun startSpan(name: String): ApmSpan {
        return span(name).start()
    }

    /**
     * 在代码块中自动管理 Span 生命周期。
     * Span 在块执行前 start，块返回后自动 end。
     *
     * @param name Span 操作名称
     * @param block 要执行的代码块，参数为活跃的 Span
     * @return 代码块的返回值
     */
    inline fun <T> traced(name: String, block: (ApmSpan) -> T): T {
        val span = startSpan(name)
        try {
            return block(span)
        } catch (e: Exception) {
            // 异常时标记 Span 为错误状态
            span.setError(e.message ?: "Unknown error")
            throw e
        } finally {
            span.end()
        }
    }
}
