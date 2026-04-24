package com.apm.core.aggregation

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EventAggregator] 和 [StackFingerprinter] 单元测试。
 *
 * 验证：
 * 1. METRIC 事件在窗口内被聚合吞入
 * 2. 窗口到期后输出聚合结果（P50/P90/P99/count）
 * 3. ALERT 事件栈指纹去重
 * 4. FILE 事件不聚合，直接通过
 * 5. flush 刷出未到期窗口
 */
class EventAggregatorTest {

    // --- METRIC 聚合测试 ---

    @Test
    fun `metric events are aggregated within window`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = true)

        // 窗口内的 METRIC 事件应被吞入，返回空列表
        val result1 = aggregator.process(createMetricEvent(fps = 58.0))
        val result2 = aggregator.process(createMetricEvent(fps = 55.0))
        val result3 = aggregator.process(createMetricEvent(fps = 60.0))

        assertTrue("Events within window should be swallowed", result1.isEmpty())
        assertTrue("Events within window should be swallowed", result2.isEmpty())
        assertTrue("Events within window should be swallowed", result3.isEmpty())
    }

    @Test
    fun `flush outputs aggregated result`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = true)

        // 添加几个 metric 事件
        aggregator.process(createMetricEvent(fps = 50.0))
        aggregator.process(createMetricEvent(fps = 60.0))
        aggregator.process(createMetricEvent(fps = 70.0))
        aggregator.process(createMetricEvent(fps = 80.0))
        aggregator.process(createMetricEvent(fps = 90.0))

        // flush 应输出聚合结果
        val results = aggregator.flush()

        assertEquals("Should output one aggregated event", 1, results.size)
        val event = results[0]
        assertEquals("frame_stats_aggregated", event.name)
        assertTrue("Should have count field", event.fields.containsKey("count"))
        assertEquals(5, event.fields["count"])

        // 应有统计字段
        assertTrue("Should have fps_p50", event.fields.containsKey("fps_p50"))
        assertTrue("Should have fps_p90", event.fields.containsKey("fps_p90"))
        assertTrue("Should have fps_p99", event.fields.containsKey("fps_p99"))
        assertTrue("Should have fps_min", event.fields.containsKey("fps_min"))
        assertTrue("Should have fps_max", event.fields.containsKey("fps_max"))
    }

    @Test
    fun `aggregation computes correct percentiles`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = true)

        // 添加 100 个事件，fps 从 1 到 100
        for (i in 1..100) {
            aggregator.process(createMetricEvent(fps = i.toDouble()))
        }

        val results = aggregator.flush()
        assertEquals(1, results.size)

        val event = results[0]
        assertEquals(100, event.fields["count"])

        // P50 ≈ 50, P90 ≈ 90, P99 ≈ 99
        val p50 = (event.fields["fps_p50"] as String).toDouble()
        val p90 = (event.fields["fps_p90"] as String).toDouble()
        val p99 = (event.fields["fps_p99"] as String).toDouble()

        assertTrue("P50 should be around 50, got $p50", p50 in 49.0..51.0)
        assertTrue("P90 should be around 90, got $p90", p90 in 89.0..91.0)
        assertTrue("P99 should be around 99, got $p99", p99 in 98.0..100.0)
    }

    // --- ALERT 去重测试 ---

    @Test
    fun `alert event with stack trace is not deduplicated on first occurrence`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = true)
        val event = createAlertEvent("NullPointerException", "at com.app.Main.doStuff(Main.java:42)")

        val result = aggregator.process(event)

        assertEquals("First alert should pass through", 1, result.size)
        assertEquals(event, result[0])
    }

    @Test
    fun `duplicate alert events are deduplicated`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = true)
        val stackTrace = "at com.app.Main.doStuff(Main.java:42)\nat com.app.Helper.process(Helper.java:10)"
        val event1 = createAlertEvent("NullPointerException", stackTrace)
        val event2 = createAlertEvent("NullPointerException", stackTrace)

        aggregator.process(event1)
        val result2 = aggregator.process(event2)

        assertTrue("Duplicate alert should be deduplicated", result2.isEmpty())
    }

    // --- FILE 事件测试 ---

    @Test
    fun `file events pass through without aggregation`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = true)
        val event = ApmEvent(
            module = "memory",
            name = "hprof_dump",
            kind = ApmEventKind.FILE,
            severity = ApmSeverity.INFO,
            fields = mapOf("file_path" to "/data/tmp/dump.hprof")
        )

        val result = aggregator.process(event)

        assertEquals("FILE events should pass through", 1, result.size)
        assertEquals(event, result[0])
    }

    // --- 禁用聚合测试 ---

    @Test
    fun `disabled aggregation passes all events through`() {
        val aggregator = EventAggregator(windowMs = Long.MAX_VALUE, enabled = false)

        val result = aggregator.process(createMetricEvent(fps = 60.0))

        assertEquals("Should pass through when disabled", 1, result.size)
    }

    // --- 辅助方法 ---

    /** 创建 FPS METRIC 事件。 */
    private fun createMetricEvent(fps: Double): ApmEvent {
        return ApmEvent(
            module = "fps",
            name = "frame_stats",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            fields = mapOf(
                "fps" to fps,
                "dropped_frames" to (60 - fps).toInt()
            )
        )
    }

    /** 创建 ALERT 事件（如崩溃）。 */
    private fun createAlertEvent(exception: String, stackTrace: String): ApmEvent {
        return ApmEvent(
            module = "crash",
            name = "java_crash",
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            fields = mapOf(
                "exception" to exception,
                "stack_trace" to stackTrace
            )
        )
    }
}
