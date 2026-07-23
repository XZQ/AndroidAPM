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
        assertEquals(0L, monitor.getTotalDispatcherModuleIsolationDropCount())
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
        assertEquals(1L, monitor.getDropCount(ApmPriority.LOW))
        assertEquals(1L, monitor.getDropCount(ApmPriority.NORMAL))
        assertEquals(2L, monitor.getDropCount(SdkDropReason.UNCLASSIFIED))
    }

    /** recordDrops 应以一次原子增量记录存储批量淘汰。 */
    @Test
    fun `recordDrops increments drop count by batch size`() {
        val monitor = SdkSelfMonitor()

        monitor.recordDrops(7)
        monitor.recordDrops(0)

        assertEquals(7L, monitor.getTotalDropCount())
        assertEquals(7L, monitor.getUnattributedDropPriorityCount())
    }

    /** 模块隔离丢弃必须同时进入专用指标和总丢弃数。 */
    @Test
    fun `module isolation drop updates aggregate and dedicated counters`() {
        val monitor = SdkSelfMonitor()

        monitor.recordDispatcherModuleIsolationDrop(ApmPriority.NORMAL)

        assertEquals(1L, monitor.getTotalDropCount())
        assertEquals(1L, monitor.getTotalDispatcherModuleIsolationDropCount())
        val report = monitor.generateReport()
        assertEquals(1L, report.dropCount)
        assertEquals(1L, report.dispatcherModuleIsolationDropCount)
        assertEquals(1L, report.dropCountsByReason[SdkDropReason.DISPATCHER_MODULE_ISOLATION.name])
        assertEquals(1L, report.dropCountsByPriority[ApmPriority.NORMAL.name])
        assertEquals(0L, monitor.getTotalDispatcherModuleIsolationDropCount())
    }

    /** Drop reason and priority remain orthogonal and reconcile to the aggregate count. */
    @Test
    fun `drop report classifies reasons priorities and unattributed totals`() {
        val monitor = SdkSelfMonitor()

        monitor.recordDrop(ApmPriority.CRITICAL, SdkDropReason.DISPATCHER_QUEUE_FULL)
        monitor.recordDrops(2, ApmPriority.LOW, SdkDropReason.RATE_LIMIT)
        monitor.recordDropsByPriority(
            totalCount = 4,
            priorityCounts = mapOf(ApmPriority.NORMAL to 2, ApmPriority.HIGH to 1),
            reason = SdkDropReason.STORAGE_CAPACITY_EVICTED
        )

        val report = monitor.generateReport()

        assertEquals(7L, report.dropCount)
        assertEquals(1L, report.dropCountsByReason[SdkDropReason.DISPATCHER_QUEUE_FULL.name])
        assertEquals(2L, report.dropCountsByReason[SdkDropReason.RATE_LIMIT.name])
        assertEquals(4L, report.dropCountsByReason[SdkDropReason.STORAGE_CAPACITY_EVICTED.name])
        assertEquals(2L, report.dropCountsByPriority[ApmPriority.LOW.name])
        assertEquals(2L, report.dropCountsByPriority[ApmPriority.NORMAL.name])
        assertEquals(1L, report.dropCountsByPriority[ApmPriority.HIGH.name])
        assertEquals(1L, report.dropCountsByPriority[ApmPriority.CRITICAL.name])
        assertEquals(1L, report.dropCountsByPriority[SdkSelfMonitor.UNATTRIBUTED_PRIORITY])
        assertEquals(0L, monitor.getDropCount(SdkDropReason.RATE_LIMIT))
        assertEquals(0L, monitor.getDropCount(ApmPriority.CRITICAL))
        assertEquals(0L, monitor.getUnattributedDropPriorityCount())
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

    /** Queue pressure snapshots preserve both count and retained bytes. */
    @Test
    fun `updateQueuePressure updates snapshots`() {
        val monitor = SdkSelfMonitor()
        monitor.updateQueuePressure(42, 8_192L)
        val report = monitor.generateReport()
        assertEquals(42, report.queueSize)
        assertEquals(8_192L, report.queueBytes)
    }

    /** Dispatcher stage histograms expose bounded interval tail evidence and reset coherently. */
    @Test
    fun `dispatcher stage latency report includes average p95 upper bound and max`() {
        val monitor = SdkSelfMonitor()
        repeat(20) { index ->
            monitor.recordDispatcherStageLatency(
                DispatcherStage.RESOLVE,
                elapsedNanos = (index + 1L) * NANOS_PER_MICROSECOND
            )
        }
        monitor.recordDispatcherStageLatency(DispatcherStage.SANITIZE, elapsedNanos = -1L)

        val report = monitor.generateReport()
        val resolve = checkNotNull(report.dispatcherStageLatencies[DispatcherStage.RESOLVE.fieldName])
        val sanitize = checkNotNull(report.dispatcherStageLatencies[DispatcherStage.SANITIZE.fieldName])

        assertEquals(20L, resolve.sampleCount)
        assertEquals(11L, resolve.averageMicros)
        assertEquals(32L, resolve.p95UpperBoundMicros)
        assertEquals(20L, resolve.maxMicros)
        assertEquals(1L, sanitize.sampleCount)
        assertEquals(0L, sanitize.maxMicros)
        val reset = checkNotNull(
            monitor.generateReport().dispatcherStageLatencies[DispatcherStage.RESOLVE.fieldName]
        )
        assertEquals(0L, reset.sampleCount)
        assertEquals(0L, reset.p95UpperBoundMicros)
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
            queueBytes = 2_048L,
            avgUploadLatencyMs = 200L,
            maxUploadLatencyMs = 800L
        )
        val event = report.toApmEvent()
        assertEquals("sdk_self_monitor", event.module)
        assertEquals("sdk_health_report", event.name)
        assertEquals(ApmPriority.HIGH, event.priority)
        assertNotNull(event.fields["emitCount"])
        assertNotNull(event.fields["dropCount"])
        assertNotNull(event.fields["queueSize"])
        assertEquals(2_048L, event.fields["queueBytes"])
    }

    /** Runtime health fields must include internal and diagnostics-sink failures. */
    @Test
    fun `health fields include internal and diagnostic failures`() {
        val report = SdkHealthReport(
            emitCount = 1L,
            dropCount = 0L,
            queueSize = 0,
            avgUploadLatencyMs = 2L,
            maxUploadLatencyMs = 3L,
            internalErrorCount = 4L,
            dispatcherModuleIsolationDropCount = 5L,
            diagnosticDroppedCount = 6L,
            diagnosticWriteFailureCount = 7L
        )

        val fields = report.toCoreHealthFields()

        assertEquals(4L, fields["internalErrorCount"])
        assertEquals(5L, fields["dispatcherModuleIsolationDropCount"])
        assertEquals(6L, fields["diagnosticDroppedCount"])
        assertEquals(7L, fields["diagnosticWriteFailureCount"])
    }

    /** Health telemetry flattens bounded classification maps into typed numeric fields. */
    @Test
    fun `health fields flatten drop reason and priority counts`() {
        val report = SdkHealthReport(
            emitCount = 2L,
            dropCount = 2L,
            queueSize = 0,
            avgUploadLatencyMs = 0L,
            maxUploadLatencyMs = 0L,
            dropCountsByReason = mapOf(SdkDropReason.RATE_LIMIT.name to 2L),
            dropCountsByPriority = mapOf(ApmPriority.LOW.name to 2L)
        )

        val fields = report.toCoreHealthFields()

        assertEquals(2L, fields["dropReason.rate_limit"])
        assertEquals(2L, fields["dropPriority.low"])
        assertTrue(report.toDiagnosticSummary().contains("dropReason.rate_limit=2"))
        assertTrue(report.toDiagnosticSummary().contains("dropPriority.low=2"))
    }

    /** Health telemetry and the independent journal share deterministic stage-latency fields. */
    @Test
    fun `health fields flatten dispatcher stage latency evidence`() {
        val report = SdkHealthReport(
            emitCount = 1L,
            dropCount = 0L,
            queueSize = 0,
            avgUploadLatencyMs = 0L,
            maxUploadLatencyMs = 0L,
            dispatcherStageLatencies = mapOf(
                DispatcherStage.RESOLVE.fieldName to DispatcherStageLatencyReport(
                    sampleCount = 3L,
                    averageMicros = 4L,
                    p95UpperBoundMicros = 8L,
                    maxMicros = 7L
                )
            )
        )

        val fields = report.toCoreHealthFields()

        assertEquals(3L, fields["dispatcherStage.resolve.count"])
        assertEquals(4L, fields["dispatcherStage.resolve.avgMicros"])
        assertEquals(8L, fields["dispatcherStage.resolve.p95UpperBoundMicros"])
        assertEquals(7L, fields["dispatcherStage.resolve.maxMicros"])
        assertEquals(0L, fields["dispatcherStage.storeHandoff.count"])
        assertTrue(report.toDiagnosticSummary().contains("dispatcherStage.resolve.count=3"))
        assertTrue(
            report.toDiagnosticSummary().contains(
                "dispatcherStage.resolve.p95UpperBoundMicros=8"
            )
        )
    }

    /** 独立诊断摘要只包含有界数值健康字段。 */
    @Test
    fun `health diagnostic summary contains all health counters`() {
        val report = SdkHealthReport(
            emitCount = 10L,
            dropCount = 2L,
            queueSize = 3,
            queueBytes = 1_024L,
            avgUploadLatencyMs = 4L,
            maxUploadLatencyMs = 5L,
            internalErrorCount = 6L,
            dispatcherModuleIsolationDropCount = 7L,
            diagnosticDroppedCount = 8L,
            diagnosticWriteFailureCount = 9L
        )

        val summary = report.toDiagnosticSummary()

        assertTrue(summary.contains("emitCount=10"))
        assertTrue(summary.contains("queueBytes=1024"))
        assertTrue(summary.contains("dropRate=0.2000"))
        assertTrue(summary.contains("internalErrorCount=6"))
        assertTrue(summary.contains("dispatcherModuleIsolationDropCount=7"))
        assertTrue(summary.contains("diagnosticWriteFailureCount=9"))
    }

    /** 独立诊断写入失败不能阻断高优先级健康事件。 */
    @Test
    fun `health publishing isolates diagnostic failure and emits high priority event`() {
        val report = healthReport(dropCount = 1L, avgUploadLatencyMs = 2L)
        var emittedPriority: ApmPriority? = null
        var emittedFields: Map<String, Any>? = null

        publishSdkHealthReport(
            report = report,
            diagnosticsSink = { throw IllegalStateException("diagnostics unavailable") },
            eventSink = { priority, fields ->
                emittedPriority = priority
                emittedFields = fields
            }
        )

        assertEquals(ApmPriority.HIGH, emittedPriority)
        assertEquals(1L, emittedFields?.get("dropCount"))
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

    /** AutoThrottle 恢复前必须连续满足健康门槛，避免模块频繁启停。 */
    @Test
    fun `autoThrottle recovers only after three consecutive healthy periods`() {
        val controller = AutoThrottleController()
        val degraded = healthReport(dropCount = 60L, avgUploadLatencyMs = 2_000L)
        val healthy = healthReport(dropCount = 5L, avgUploadLatencyMs = 500L)

        val initialDecision = controller.evaluate(degraded)
        assertTrue(initialDecision.modulesToThrottle.contains("battery"))

        repeat(2) {
            val waitingDecision = controller.evaluate(healthy)
            assertTrue(waitingDecision.modulesToThrottle.contains("battery"))
            assertTrue(waitingDecision.modulesToRecover.isEmpty())
        }

        val recoveryDecision = controller.evaluate(healthy)
        assertTrue(recoveryDecision.modulesToThrottle.isEmpty())
        assertTrue(recoveryDecision.modulesToRecover.contains("battery"))
    }

    /** 迟滞区间报告不能计入连续健康周期。 */
    @Test
    fun `autoThrottle neutral period resets recovery streak`() {
        val controller = AutoThrottleController()
        val degraded = healthReport(dropCount = 60L, avgUploadLatencyMs = 2_000L)
        val healthy = healthReport(dropCount = 5L, avgUploadLatencyMs = 500L)
        val neutral = healthReport(dropCount = 30L, avgUploadLatencyMs = 5_000L)

        controller.evaluate(degraded)
        repeat(2) { controller.evaluate(healthy) }
        val neutralDecision = controller.evaluate(neutral)
        assertTrue(neutralDecision.modulesToThrottle.contains("battery"))

        repeat(2) {
            val waitingDecision = controller.evaluate(healthy)
            assertTrue(waitingDecision.modulesToRecover.isEmpty())
        }
        assertTrue(controller.evaluate(healthy).modulesToRecover.contains("battery"))
    }

    /** 更严重退化会扩大关闭范围，并在恢复时一次释放完整集合。 */
    @Test
    fun `autoThrottle retains expanded degradation until recovery`() {
        val controller = AutoThrottleController()
        controller.evaluate(healthReport(dropCount = 60L, avgUploadLatencyMs = 2_000L))

        val expandedDecision = controller.evaluate(
            healthReport(dropCount = 85L, avgUploadLatencyMs = 5_000L)
        )
        assertTrue(expandedDecision.modulesToThrottle.contains("battery"))
        assertTrue(expandedDecision.modulesToThrottle.contains("network"))

        val healthy = healthReport(dropCount = 5L, avgUploadLatencyMs = 500L)
        repeat(2) { controller.evaluate(healthy) }
        val recoveryDecision = controller.evaluate(healthy)
        assertTrue(recoveryDecision.modulesToRecover.contains("battery"))
        assertTrue(recoveryDecision.modulesToRecover.contains("network"))
    }

    /** 构造固定 100 次 emit 的健康报告，便于直接表达百分比。 */
    private fun healthReport(dropCount: Long, avgUploadLatencyMs: Long): SdkHealthReport =
        SdkHealthReport(
            emitCount = 100L,
            dropCount = dropCount,
            queueSize = 0,
            avgUploadLatencyMs = avgUploadLatencyMs,
            maxUploadLatencyMs = avgUploadLatencyMs
        )

    companion object {
        /** Nanoseconds per microsecond for deterministic latency histogram inputs. */
        private const val NANOS_PER_MICROSECOND = 1_000L
    }
}
