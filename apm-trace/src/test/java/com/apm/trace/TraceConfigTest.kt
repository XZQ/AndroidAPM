package com.apm.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TraceConfig 契约测试。
 *
 * 锁定默认值契约与两种此前无直接覆盖的行为：
 * 1. `enabled=false` 时 `traced` 代码块仍执行并返回原值，Span 不记录任何时间戳；
 * 2. `maxSpanDurationMs` 超时分支可安全执行（end 晚于 deadline 仍正常收尾）。
 */
class TraceConfigTest {

    /** 每次测试前重置全局配置，避免跨用例污染。 */
    @Before
    fun setUp() {
        ApmTrace.config = TraceConfig(enabled = true, autoReport = false)
    }

    /** 默认值契约：生产接入方依赖这些默认行为，改变即破坏兼容。 */
    @Test
    fun config_defaults() {
        val config = TraceConfig()
        assertTrue(config.enabled)
        assertEquals(0L, config.maxSpanDurationMs)
        assertTrue(config.autoReport)
        assertEquals("trace", config.reportModule)
        assertEquals(32, config.maxAttributes)
    }

    /** 禁用配置下 `traced` 块正常执行并返回原值，Span 全程不记录。 */
    @Test
    fun traced_disabledConfig_stillRunsBlockWithoutRecording() {
        ApmTrace.config = TraceConfig(enabled = false, autoReport = false)

        var spanInsideBlock: ApmSpan? = null
        val result = ApmTrace.traced("disabled_block") { span ->
            spanInsideBlock = span
            "original_value"
        }

        assertEquals("original_value", result)
        val span = spanInsideBlock!!
        // 禁用时不记录时间戳，end 也不改变完成状态
        assertEquals(-1L, span.startTimestampMs)
        assertFalse(span.isFinished)
    }

    /** 超时分支：deadline 已过时 end 仍安全收尾，不抛异常。 */
    @Test
    fun span_maxDurationTimeout_endStillCompletes() {
        ApmTrace.config = TraceConfig(
            enabled = true,
            autoReport = false,
            maxSpanDurationMs = 1L
        )

        val span = ApmTrace.span("timeout_test").start()
        Thread.sleep(5L)
        span.end()

        assertTrue(span.isFinished)
        assertTrue(span.durationMs >= 0L)
    }

    /** 无超时限制（maxSpanDurationMs=0）时不启用超时标记。 */
    @Test
    fun span_zeroMaxDuration_neverTimesOut() {
        val span = ApmTrace.span("no_timeout_test").start()
        Thread.sleep(2L)
        span.end()

        assertTrue(span.isFinished)
    }
}
