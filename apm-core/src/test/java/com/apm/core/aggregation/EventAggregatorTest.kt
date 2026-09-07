package com.apm.core.aggregation

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmOccurrenceContext
import com.apm.model.ApmPriority
import com.apm.model.ApmBatchEnvelopeSerializer
import com.apm.model.ApmResourceContext
import com.apm.model.ApmEventCodec
import com.apm.core.privacy.PiiSanitizer
import java.util.Locale
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

    /** Aggregates retain event-time identity/dimensions and remain typed across privacy and codec. */
    @Test
    fun `aggregate preserves occurrence dimensions and typed statistics under any locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val aggregator = EventAggregator()
            val identity = ApmOccurrenceContext("1.0", "1", "build-1", "release", "test-installation")
            val event = ApmEvent("fps", "frame", severity = ApmSeverity.WARN, priority = ApmPriority.HIGH,
                processName = "sample", threadName = "render", scene = "Feed", foreground = true,
                fields = mapOf("ms" to 12.5, "route" to "/feed"),
                globalContext = mapOf("environment" to "qa"), extras = mapOf("channel" to "internal")
            ).withOccurrenceContext(identity)
            aggregator.process(event)
            aggregator.process(event.copy(fields = mapOf("ms" to 17.5, "route" to "/feed")).withOccurrenceContext(identity))
            val result = ApmEventCodec.decode(ApmEventCodec.encode(PiiSanitizer().sanitize(aggregator.flush().single())))
            assertEquals(identity, result.occurrence)
            assertEquals(event.priority, result.priority)
            assertEquals(event.severity, result.severity)
            assertEquals(event.processName, result.processName)
            assertEquals(event.threadName, result.threadName)
            assertEquals(event.scene, result.scene)
            assertEquals(event.foreground, result.foreground)
            assertEquals(event.globalContext, result.globalContext)
            assertEquals(event.extras, result.extras)
            assertEquals("/feed", result.fields["route"])
            assertEquals(15.0, result.fields["ms_p50"])
            assertEquals(2, result.fields["ms_sample_count"])
            assertTrue(result.eventId != event.eventId)
            assertEquals(1, ApmBatchEnvelopeSerializer.serializeV3(listOf(result), ApmResourceContext()).eventCount)
        } finally { Locale.setDefault(previous) }
    }

    /** Scene, historical build, raw dimensions and slash-separated names cannot merge. */
    @Test
    fun `aggregation isolates dimensions and identities within bucket bound`() {
        val identity = ApmOccurrenceContext("1", "1", "old", "release", "test-installation")
        val base = ApmEvent("a/b", "c", scene = "Feed", fields = mapOf("ms" to 1.0, "route" to "a"))
            .withOccurrenceContext(identity)
        val events = listOf(base, base.copy(scene = "Detail").withOccurrenceContext(identity),
            base.withOccurrenceContext(identity.copy(appBuild = "new")),
            base.copy(fields = mapOf("ms" to 1.0, "route" to "b")).withOccurrenceContext(identity),
            base.copy(module = "a", name = "b/c").withOccurrenceContext(identity))
        val aggregator = EventAggregator(maxBuckets = 2)
        val output = events.flatMap(aggregator::process) + aggregator.flush()
        assertEquals(events.size, output.size)
        assertTrue(output.all { it.fields["count"] == 1 && it.occurrence != null })
    }

    /** Unsupported metric shapes are delivered intact instead of disappearing in an empty bucket. */
    @Test
    fun `text metrics and colliding statistic dimensions bypass aggregation`() {
        val aggregator = EventAggregator()
        val text = ApmEvent("sample", "message", fields = mapOf("detail" to "ready"))
        val collision = text.copy(fields = mapOf("ms" to 12, "ms_p50" to "dimension"))
        assertEquals(listOf(text), aggregator.process(text))
        assertEquals(listOf(collision), aggregator.process(collision))
        assertTrue(aggregator.flush().isEmpty())
    }

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
        val p50 = event.fields["fps_p50"] as Double
        val p90 = event.fields["fps_p90"] as Double
        val p99 = event.fields["fps_p99"] as Double

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
    /** Expiration uses monotonic time while collector timestamps remain Unix epoch values. */
    @Test
    fun `expired window keeps wall timestamps in output`() {
        val aggregator = EventAggregator(windowMs = 1L, enabled = true)
        aggregator.process(createMetricEvent(fps = 60.0))

        val result = aggregator.flushExpired(nowElapsedMs = Long.MAX_VALUE).single()

        assertTrue((result.fields["window_start_ms"] as Long) > 1_500_000_000_000L)
        assertEquals(result.timestamp, result.fields["window_end_ms"])
    }

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
