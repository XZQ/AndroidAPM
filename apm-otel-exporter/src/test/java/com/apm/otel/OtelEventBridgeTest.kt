package com.apm.otel

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtelEventBridge 单元测试。
 */
class OtelEventBridgeTest {

    /** 测试 ALERT 事件导出为 Span + Log。 */
    @Test
    fun alertEvent_exportsSpanAndLog() {
        val bridge = OtelEventBridge(OtelConfig(enabled = true, exportSpans = true, exportLogs = true))
        val event = ApmEvent(
            module = "crash", name = "java_crash",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.ERROR,
            fields = mapOf("exception" to "NullPointerException")
        )

        val result = bridge.export(event)
        assertEquals(1, result.spans.size)
        assertEquals(0, result.metrics.size)
        assertNotNull(result.log)
    }

    /** 测试 METRIC 事件导出为 Metric + Log。 */
    @Test
    fun metricEvent_exportsMetricsAndLog() {
        val bridge = OtelEventBridge(OtelConfig(enabled = true, exportMetrics = true, exportLogs = true))
        val event = ApmEvent(
            module = "fps", name = "frame_stats",
            kind = ApmEventKind.METRIC, severity = ApmSeverity.INFO,
            fields = mapOf("fps" to 60.0, "dropped" to 2)
        )

        val result = bridge.export(event)
        assertEquals(0, result.spans.size)
        assertEquals(2, result.metrics.size)
        assertNotNull(result.log)
    }

    /** 测试禁用时不导出。 */
    @Test
    fun disabledConfig_returnsEmpty() {
        val bridge = OtelEventBridge(OtelConfig(enabled = false))
        val event = ApmEvent(
            module = "crash", name = "test",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.ERROR
        )

        val result = bridge.export(event)
        assertTrue(result.spans.isEmpty())
        assertTrue(result.metrics.isEmpty())
        assertNull(result.log)
    }

    /** 测试批量导出。 */
    @Test
    fun batchExport() {
        val bridge = OtelEventBridge(OtelConfig(enabled = true, exportSpans = true, exportMetrics = true, exportLogs = false))
        val events = listOf(
            ApmEvent(module = "crash", name = "crash1", kind = ApmEventKind.ALERT, severity = ApmSeverity.ERROR),
            ApmEvent(module = "fps", name = "fps1", kind = ApmEventKind.METRIC, fields = mapOf("fps" to 60.0)),
            ApmEvent(module = "crash", name = "crash2", kind = ApmEventKind.ALERT, severity = ApmSeverity.WARN)
        )

        val result = bridge.exportBatch(events)
        assertEquals(2, result.spans.size)
        assertEquals(1, result.metrics.size)
        assertNull(result.log)
    }

    /** 测试 Log body 包含关键信息。 */
    @Test
    fun logBody_containsKeyInfo() {
        val bridge = OtelEventBridge(OtelConfig(enabled = true, exportLogs = true))
        val event = ApmEvent(
            module = "memory", name = "heap_warning",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.WARN,
            fields = mapOf("pss_mb" to 512)
        )

        val result = bridge.export(event)
        assertNotNull(result.log)
        val body = result.log!!["body"] as String
        assertTrue(body.contains("memory"))
        assertTrue(body.contains("heap_warning"))
        assertTrue(body.contains("WARN"))
    }
}
