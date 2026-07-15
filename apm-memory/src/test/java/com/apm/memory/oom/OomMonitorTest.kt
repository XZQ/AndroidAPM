package com.apm.memory.oom

import com.apm.memory.MemoryConfig
import com.apm.memory.MemoryReport
import com.apm.memory.MemoryReportSink
import com.apm.memory.MemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral tests for independent OOM warning dimensions. */
class OomMonitorTest {
    /** Java heap warning produces a bounded warning report. */
    @Test
    fun `heap warning emits oom warn`() {
        val sink = RecordingOomSink()
        val monitor = OomMonitor(MemoryConfig(javaHeapWarnRatio = 0.5f), null, sink)

        monitor.check(MemorySnapshot(javaHeapUsedMb = 60, javaHeapMaxMb = 100))

        assertEquals(listOf("oom_warn"), sink.reports.map(MemoryReport::name))
    }

    /** Critical heap usage remains reportable when heap dumping is disabled. */
    @Test
    fun `critical heap emits without dumper`() {
        val sink = RecordingOomSink()
        val monitor = OomMonitor(
            MemoryConfig(javaHeapWarnRatio = 0.5f, javaHeapCriticalRatio = 0.8f),
            null,
            sink
        )

        monitor.check(MemorySnapshot(javaHeapUsedMb = 90, javaHeapMaxMb = 100))

        assertEquals(listOf("oom_critical"), sink.reports.map(MemoryReport::name))
    }

    /** System low-memory and near-threshold signals remain independently visible. */
    @Test
    fun `system pressure emits both dimensions`() {
        val sink = RecordingOomSink()
        val monitor = OomMonitor(MemoryConfig(), null, sink)

        monitor.check(
            MemorySnapshot(
                isLowMemory = true,
                systemAvailMemKb = 120,
                lowMemThresholdKb = 100
            )
        )

        assertEquals(
            listOf("system_low_memory", "system_mem_warn"),
            sink.reports.map(MemoryReport::name)
        )
    }

    /** Native heap pressure uses the configured threshold in its payload. */
    @Test
    fun `native heap warning carries threshold`() {
        val sink = RecordingOomSink()
        val monitor = OomMonitor(MemoryConfig(nativeHeapWarnKb = 100), null, sink)

        monitor.check(MemorySnapshot(nativeHeapAllocatedKb = 101))

        val report = sink.reports.single()
        assertEquals("native_heap_warn", report.name)
        assertEquals(100L, report.fields["nativeHeapWarnKb"])
        assertTrue((report.fields["nativeHeapAllocatedKb"] as Long) > 100L)
    }
}

/** Captures OOM reports for deterministic assertions. */
private class RecordingOomSink : MemoryReportSink {
    /** Reports emitted during one test. */
    val reports = mutableListOf<MemoryReport>()

    /** Appends one report in emission order. */
    override fun emit(report: MemoryReport) {
        reports += report
    }
}
