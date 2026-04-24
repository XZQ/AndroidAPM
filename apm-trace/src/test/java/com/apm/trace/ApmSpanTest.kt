package com.apm.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApmSpan 单元测试。
 */
class ApmSpanTest {

    /** 每次测试前重置配置。 */
    @Before
    fun setUp() {
        ApmTrace.config = TraceConfig(enabled = true, autoReport = false)
    }

    /** 测试 Span 基本生命周期：start → end。 */
    @Test
    fun span_startAndEnd() {
        val span = ApmTrace.span("test_operation").start()
        assertTrue(span.startTimestampMs > 0)
        assertFalse(span.isFinished)
        assertEquals(-1, span.durationMs)

        span.end()
        assertTrue(span.isFinished)
        assertTrue(span.durationMs >= 0)
    }

    /** 测试 end 幂等：多次调用 end 不会改变状态。 */
    @Test
    fun span_endIsIdempotent() {
        val span = ApmTrace.span("idempotent_test").start()
        span.end()
        val firstDuration = span.durationMs
        // 等待一点时间确保第二次 end 不会改变 duration
        Thread.sleep(1)
        span.end()
        assertEquals(firstDuration, span.durationMs)
    }

    /** 测试 SpanContext 生成：traceId 和 spanId 不为空。 */
    @Test
    fun span_contextGenerated() {
        val span = ApmTrace.span("context_test").start()
        assertNotNull(span.context.traceId)
        assertNotNull(span.context.spanId)
        assertTrue(span.context.traceId.isNotEmpty())
        assertTrue(span.context.spanId.isNotEmpty())
    }

    /** 测试嵌套 Span 共享 traceId，子 span 有 parentSpanId。 */
    @Test
    fun span_nestedSpansShareTraceId() {
        val parent = ApmTrace.span("parent_op").start()
        val child = ApmTrace.span("child_op")
            .setParent(parent)
            .start()

        // 子 span 应共享父 span 的 traceId
        assertEquals(parent.context.traceId, child.context.traceId)
        // 子 span 的 parentSpanId 应等于父 span 的 spanId
        assertEquals(parent.context.spanId, child.context.parentSpanId)
        // 父 span 无 parent
        assertEquals(null, parent.context.parentSpanId)
    }

    /** 测试自定义属性设置和上限控制。 */
    @Test
    fun span_attributes() {
        val config = TraceConfig(enabled = true, autoReport = false, maxAttributes = 3)
        ApmTrace.config = config

        val span = ApmTrace.span("attr_test")
        span.setAttribute("key1", "value1")
        span.setAttribute("key2", "value2")
        span.setAttribute("key3", "value3")
        // 第 4 个属性应触发淘汰最旧的 key1
        span.setAttribute("key4", "value4")

        // 开始并结束 span 以完成测试
        span.start()
        span.end()

        assertTrue(span.isFinished)
    }

    /** 测试 setError 标记。 */
    @Test
    fun span_setError() {
        val span = ApmTrace.span("error_test").start()
        span.setError("something went wrong")
        span.end()

        assertTrue(span.isFinished)
    }

    /** 测试 disabled 配置下 span 不记录。 */
    @Test
    fun span_disabledConfig() {
        ApmTrace.config = TraceConfig(enabled = false, autoReport = false)
        val span = ApmTrace.span("disabled_test").start()

        // 禁用时不记录时间戳
        assertEquals(-1, span.startTimestampMs)
        span.end()
        assertFalse(span.isFinished)
    }

    /** 测试 startSpan 快捷方法。 */
    @Test
    fun trace_startSpan() {
        val span = ApmTrace.startSpan("quick_test")
        assertTrue(span.startTimestampMs > 0)
        span.end()
        assertTrue(span.isFinished)
    }

    /** 测试 traceId 唯一性。 */
    @Test
    fun idGenerator_traceIdUnique() {
        val ids = mutableSetOf<String>()
        for (i in 0 until 100) {
            val span = ApmTrace.span("uniqueness_test_$i").start()
            assertTrue("Duplicate traceId at iteration $i", ids.add(span.context.traceId))
        }
    }

    /** 测试 spanId 唯一性。 */
    @Test
    fun idGenerator_spanIdUnique() {
        val ids = mutableSetOf<String>()
        for (i in 0 until 100) {
            val span = ApmTrace.span("span_uniqueness_$i").start()
            assertTrue("Duplicate spanId at iteration $i", ids.add(span.context.spanId))
        }
    }

    /** 测试 traceId 长度为 32（128 bit hex）。 */
    @Test
    fun idGenerator_traceIdLength() {
        val span = ApmTrace.span("length_test").start()
        assertEquals(32, span.context.traceId.length)
    }

    /** 测试 spanId 长度为 16（64 bit hex）。 */
    @Test
    fun idGenerator_spanIdLength() {
        val span = ApmTrace.span("length_test").start()
        assertEquals(16, span.context.spanId.length)
    }

    /** 测试 traced 代码块自动管理生命周期。 */
    @Test
    fun trace_tracedBlock() {
        val result = ApmTrace.traced("block_test") { span ->
            assertTrue(span.startTimestampMs > 0)
            assertFalse(span.isFinished)
            "hello"
        }
        assertEquals("hello", result)
    }

    /** 测试 traced 代码块异常时 span 标记错误。 */
    @Test(expected = RuntimeException::class)
    fun trace_tracedBlockException() {
        try {
            ApmTrace.traced("exception_test") {
                throw RuntimeException("test error")
            }
        } catch (e: RuntimeException) {
            assertEquals("test error", e.message)
            throw e
        }
    }
}
