package com.apm.otel

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtelMetricExporter 单元测试。
 */
class OtelMetricExporterTest {

    /** 测试 METRIC 事件转换为 Metric 数据点。 */
    @Test
    fun metricEvent_convertsToDataPoints() {
        val event = ApmEvent(
            module = "fps",
            name = "frame_stats",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            fields = mapOf(
                "fps" to 58.5,
                "dropped_frames" to 3,
                "jank_count" to 1.0
            )
        )

        val metrics = OtelMetricExporter.toMetricData(event)
        assertEquals(3, metrics.size)

        // 验证 metric name 前缀
        metrics.forEach { dp ->
            assertTrue((dp["metricName"] as String).startsWith("apm.fps.frame_stats."))
            assertEquals("GAUGE", dp["type"])
            assertEquals(event.timestamp, dp["epochMs"])
        }

        // 验证数值
        val names = metrics.map { it["metricName"] as String }.toSet()
        assertTrue(names.contains("apm.fps.frame_stats.fps"))
        assertTrue(names.contains("apm.fps.frame_stats.dropped_frames"))
        assertTrue(names.contains("apm.fps.frame_stats.jank_count"))
    }

    /** 测试 ALERT 事件不生成 Metric。 */
    @Test
    fun alertEvent_returnsEmpty() {
        val event = ApmEvent(
            module = "crash", name = "java_crash",
            kind = ApmEventKind.ALERT, severity = ApmSeverity.ERROR
        )
        assertTrue(OtelMetricExporter.toMetricData(event).isEmpty())
    }

    /** 测试无数值字段时不生成 Metric。 */
    @Test
    fun noNumericFields_returnsEmpty() {
        val event = ApmEvent(
            module = "test", name = "string_only",
            kind = ApmEventKind.METRIC,
            fields = mapOf("label" to "some_string")
        )
        assertTrue(OtelMetricExporter.toMetricData(event).isEmpty())
    }

    /** 测试批量转换。 */
    @Test
    fun batchExport() {
        val events = listOf(
            ApmEvent(
                module = "fps", name = "frame1",
                kind = ApmEventKind.METRIC,
                fields = mapOf("fps" to 60.0)
            ),
            ApmEvent(
                module = "memory", name = "heap",
                kind = ApmEventKind.METRIC,
                fields = mapOf("pss_mb" to 128.5, "heap_mb" to 64.0)
            )
        )
        val metrics = OtelMetricExporter.toMetricDataBatch(events)
        assertEquals(3, metrics.size)
    }

    /** 测试属性包含 scene 和 foreground。 */
    @Test
    fun metricData_includesOptionalAttributes() {
        val event = ApmEvent(
            module = "fps", name = "frame",
            kind = ApmEventKind.METRIC,
            scene = "MainActivity",
            foreground = true,
            fields = mapOf("fps" to 55.0)
        )

        val metrics = OtelMetricExporter.toMetricData(event)
        assertEquals(1, metrics.size)

        @Suppress("UNCHECKED_CAST")
        val attrs = metrics[0]["attributes"] as Map<String, String>
        assertEquals("MainActivity", attrs["apm.scene"])
        assertEquals("true", attrs["app.foreground"])
    }
}
