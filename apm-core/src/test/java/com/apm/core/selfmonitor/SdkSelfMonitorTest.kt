package com.apm.core.selfmonitor

import com.apm.model.ApmPriority
import org.junit.Assert.*
import org.junit.Test

/**
 * SdkSelfMonitor 和关联组件测试。
 * 验证计数器准确性、报告生成、降级策略。
 */
class SdkSelfMonitorTest {

    /** 初始状态下计数器应为零。 */
    @Test
    fun `initial counters are zero`() {
        val monitor = SdkSelfMonitor()
        assertEquals(0L, monitor.getTotalEmitCount())
        assertEquals(0L, monitor.getTotalDropCount())
    }

    /** recordEmit 应递增发射计数。 */
    @Test
    fun `recordEmit increments emit count`() {
        val monitor = SdkSelfMonitor()
        // 记录 3 次发射
        repeat(3) { monitor.recordEmit() }
        assertEquals(3L, monitor.getTotalEmitCount())
    }

    /** recordDrop 应递增丢弃计数。 */
    @Test
    fun `recordDrop increments drop count`() {
        val monitor = SdkSelfMonitor()
        // 记录 2 次丢弃
        monitor.recordDrop(ApmPriority.LOW)
        monitor.recordDrop(ApmPriority.NORMAL)
        assertEquals(2L, monitor.getTotalDropCount())
    }

    /** recordUploadLatency 应更新最大延迟。 */
    @Test
    fun `recordUploadLatency updates max latency`() {
        val monitor = SdkSelfMonitor()
        // 记录递增的上传延迟
        monitor.recordUploadLatency(100L)
        monitor.recordUploadLatency(500L)
        monitor.recordUploadLatency(200L)

        // 生成报告检查最大延迟
        val report = monitor.generateReport()
        assertEquals(500L, report.maxUploadLatencyMs)
    }

    /** generateReport 应计算正确的平均延迟。 */
    @Test
    fun `generateReport calculates average latency`() {
        val monitor = SdkSelfMonitor()
        // 记录 3 次延迟：100 + 200 + 300 = 600，平均 200
        monitor.recordUploadLatency(100L)
        monitor.recordUploadLatency(200L)
        monitor.recordUploadLatency(300L)

        val report = monitor.generateReport()
        assertEquals(200L, report.avgUploadLatencyMs)
    }

    /** generateReport 应重置计数器。 */
    @Test
    fun `generateReport resets counters`() {
        val monitor = SdkSelfMonitor()
        monitor.recordEmit()
        monitor.recordEmit()
        monitor.recordDrop()

        // 第一次报告
        val report1 = monitor.generateReport()
        assertEquals(2L, report1.emitCount)
        assertEquals(1L, report1.dropCount)

        // 第二次报告应该是 0（计数器已重置）
        val report2 = monitor.generateReport()
        assertEquals(0L, report2.emitCount)
        assertEquals(0L, report2.dropCount)
    }

    /** updateQueueSize 应更新队列大小快照。 */
    @Test
    fun `updateQueueSize updates snapshot`() {
        val monitor = SdkSelfMonitor()
        monitor.updateQueueSize(42)
        val report = monitor.generateReport()
        assertEquals(42, report.queueSize)
    }

    /** SdkHealthReport 的 dropRate 应正确计算。 */
    @Test
    fun `health report dropRate calculation`() {
        val report = SdkHealthReport(
            emitCount = 100L,
            dropCount = 25L,
            queueSize = 10,
            avgUploadLatencyMs = 200L,
            maxUploadLatencyMs = 500L
        )
        assertEquals(0.25f, report.dropRate, 0.001f)
    }

    /** SdkHealthReport 在 emitCount 为 0 时 dropRate 应为 0。 */
    @Test
    fun `health report dropRate is zero when no events`() {
        val report = SdkHealthReport(
            emitCount = 0L,
            dropCount = 0L,
            queueSize = 0,
            avgUploadLatencyMs = 0L,
            maxUploadLatencyMs = 0L
        )
        assertEquals(0f, report.dropRate, 0.001f)
    }

    /** toApmEvent 应生成有效的 APM 事件。 */
    @Test
    fun `toApmEvent produces valid event`() {
        val report = SdkHealthReport(
            emitCount = 100L,
            dropCount = 10L,
            queueSize = 5,
            avgUploadLatencyMs = 200L,
            maxUploadLatencyMs = 800L
        )
        val event = report.toApmEvent()
        assertEquals("sdk_self_monitor", event.module)
        assertEquals("sdk_health_report", event.name)
        assertEquals(ApmPriority.LOW, event.priority)
        assertNotNull(event.fields["emitCount"])
        assertNotNull(event.fields["dropCount"])
        assertNotNull(event.fields["queueSize"])
    }

    /** AutoThrottle 在正常状态下不应建议禁用模块。 */
    @Test
    fun `autoThrottle no action when healthy`() {
        val report = SdkHealthReport(
            emitCount = 100L,
            dropCount = 5L, // 5% drop rate
            queueSize = 10,
            avgUploadLatencyMs = 500L,
            maxUploadLatencyMs = 1000L
        )
        val toDisable = AutoThrottle.computeModulesToDisable(report)
        assertTrue("No modules should be disabled when healthy", toDisable.isEmpty())
    }

    /** AutoThrottle 在丢弃率超过 50% 时应禁用 LOW 优先级模块。 */
    @Test
    fun `autoThrottle disables LOW modules at 50 percent drop rate`() {
        val report = SdkHealthReport(
            emitCount = 100L,
            dropCount = 60L, // 60% drop rate
            queueSize = 50,
            avgUploadLatencyMs = 2000L,
            maxUploadLatencyMs = 5000L
        )
        val toDisable = AutoThrottle.computeModulesToDisable(report)
        assertTrue("Should disable battery", toDisable.contains("battery"))
        assertTrue("Should disable gc_monitor", toDisable.contains("gc_monitor"))
        assertTrue("Should disable thread_monitor", toDisable.contains("thread_monitor"))
        assertTrue("Should disable render", toDisable.contains("render"))
        assertTrue("Should disable webview", toDisable.contains("webview"))
    }

    /** AutoThrottle 在丢弃率超过 80% 时应额外禁用 NORMAL 模块。 */
    @Test
    fun `autoThrottle disables NORMAL modules at 80 percent drop rate`() {
        val report = SdkHealthReport(
            emitCount = 100L,
            dropCount = 85L, // 85% drop rate
            queueSize = 100,
            avgUploadLatencyMs = 5000L,
            maxUploadLatencyMs = 10000L
        )
        val toDisable = AutoThrottle.computeModulesToDisable(report)
        // LOW 模块应被禁用
        assertTrue("Should disable battery", toDisable.contains("battery"))
        // NORMAL 模块也应被禁用
        assertTrue("Should disable fps", toDisable.contains("fps"))
        assertTrue("Should disable io", toDisable.contains("io"))
        assertTrue("Should disable network", toDisable.contains("network"))
    }

    /** AutoThrottle 在上传延迟过高时应禁用 LOW 模块。 */
    @Test
    fun `autoThrottle disables LOW modules when latency is high`() {
        val report = SdkHealthReport(
            emitCount = 100L,
            dropCount = 5L, // 低丢弃率
            queueSize = 10,
            avgUploadLatencyMs = 15_000L, // 15 秒延迟
            maxUploadLatencyMs = 20_000L
        )
        val toDisable = AutoThrottle.computeModulesToDisable(report)
        // 由于延迟高，应禁用 LOW 模块
        assertTrue("Should disable battery due to high latency", toDisable.contains("battery"))
    }
}
