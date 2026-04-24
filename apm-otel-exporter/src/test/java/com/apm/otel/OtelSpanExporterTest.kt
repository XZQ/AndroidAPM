package com.apm.otel

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtelSpanExporter 单元测试。
 */
class OtelSpanExporterTest {

    /** 测试 ALERT 事件成功转换为 Span。 */
    @Test
    fun alertEvent_convertsToSpan() {
        val event = ApmEvent(
            module = "crash",
            name = "java_crash",
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            priority = ApmPriority.CRITICAL,
            processName = "com.example",
            threadName = "main",
            scene = "MainActivity",
            fields = mapOf("exception" to "NullPointerException"),
            extras = mapOf("stack_hash" to "abc123")
        )

        val spanData = OtelSpanExporter.toSpanData(event)
        assertNotNull(spanData)

        // 验证基础字段
        assertEquals("crash", spanData!!["name"])
        assertEquals("java_crash", spanData["description"])
        assertEquals("INTERNAL", spanData["kind"])
        assertEquals("ERROR", spanData["status"])
        assertEquals(event.timestamp, spanData["startEpochMs"])

        // 验证属性
        @Suppress("UNCHECKED_CAST")
        val attrs = spanData["attributes"] as Map<String, Any>
        assertEquals("crash", attrs["apm.module"])
        assertEquals("java_crash", attrs["apm.name"])
        assertEquals("ERROR", attrs["apm.severity"])
        assertEquals("CRITICAL", attrs["apm.priority"])
        assertEquals("MainActivity", attrs["apm.scene"])
        assertEquals("NullPointerException", attrs["apm.field.exception"])
        assertEquals("abc123", attrs["extras.stack_hash"])

        // 验证 traceId 和 spanId 不为空
        assertTrue((spanData["traceId"] as String).isNotEmpty())
        assertTrue((spanData["spanId"] as String).isNotEmpty())
    }

    /** 测试 METRIC 事件不转换为 Span。 */
    @Test
    fun metricEvent_returnsNull() {
        val event = ApmEvent(
            module = "fps",
            name = "frame_stats",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO
        )
        assertNull(OtelSpanExporter.toSpanData(event))
    }

    /** 测试 FILE 事件不转换为 Span。 */
    @Test
    fun fileEvent_returnsNull() {
        val event = ApmEvent(
            module = "memory",
            name = "hprof_dump",
            kind = ApmEventKind.FILE,
            severity = ApmSeverity.INFO
        )
        assertNull(OtelSpanExporter.toSpanData(event))
    }

    /** 测试不同 severity 的 Span 状态映射。 */
    @Test
    fun spanStatus_mapping() {
        // ERROR severity → ERROR status
        val errorEvent = ApmEvent(
            module = "crash", name = "test",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.ERROR
        )
        assertEquals("ERROR", OtelSpanExporter.toSpanData(errorEvent)!!["status"])

        // WARN severity → UNSET status
        val warnEvent = ApmEvent(
            module = "memory", name = "leak",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.WARN
        )
        assertEquals("UNSET", OtelSpanExporter.toSpanData(warnEvent)!!["status"])

        // INFO severity → OK status
        val infoEvent = ApmEvent(
            module = "test", name = "info",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.INFO
        )
        assertEquals("OK", OtelSpanExporter.toSpanData(infoEvent)!!["status"])
    }
}
