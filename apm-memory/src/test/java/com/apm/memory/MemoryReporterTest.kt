package com.apm.memory

import com.apm.memory.leak.LeakResult
import com.apm.memory.leak.LeakType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral tests for memory snapshot, alert, and leak classification. */
class MemoryReporterTest {
    /** A healthy snapshot emits one metric with the captured scene and fields. */
    @Test
    fun `healthy snapshot emits metric only`() {
        val sink = RecordingMemorySink()
        val reporter = MemoryReporter(MemoryConfig(), sink)

        reporter.onSnapshot(
            MemorySnapshot(
                javaHeapUsedMb = 20,
                javaHeapMaxMb = 100,
                totalPssKb = 100,
                scene = "Home",
                foreground = true
            ),
            reason = "periodic"
        )

        assertEquals(1, sink.reports.size)
        val report = sink.reports.single()
        assertEquals("memory_snapshot", report.name)
        assertEquals("Home", report.scene)
        assertEquals("periodic", report.fields["reason"])
    }

    /** Independent heap, PSS, and system signals are preserved in one alert. */
    @Test
    fun `threshold snapshot emits all alert reasons`() {
        val sink = RecordingMemorySink()
        val reporter = MemoryReporter(
            MemoryConfig(javaHeapWarnRatio = 0.5f, totalPssWarnKb = 500),
            sink
        )

        reporter.onSnapshot(
            MemorySnapshot(
                javaHeapUsedMb = 60,
                javaHeapMaxMb = 100,
                totalPssKb = 600,
                isLowMemory = true
            ),
            reason = "manual"
        )

        assertEquals(2, sink.reports.size)
        val alert = sink.reports.last()
        assertEquals("memory_alert", alert.name)
        assertEquals("java_heap_ratio,total_pss,system_low_memory", alert.fields["alertReasons"])
    }

    /** Disabling periodic metrics does not suppress a threshold alert. */
    @Test
    fun `alert survives snapshot reporting disablement`() {
        val sink = RecordingMemorySink()
        val reporter = MemoryReporter(
            MemoryConfig(reportEverySnapshot = false, javaHeapWarnRatio = 0.5f),
            sink
        )

        reporter.onSnapshot(
            MemorySnapshot(javaHeapUsedMb = 80, javaHeapMaxMb = 100),
            reason = "threshold"
        )

        assertEquals(listOf("memory_alert"), sink.reports.map(MemoryReport::name))
    }

    /** Leak reports preserve type, retained count, scene, and suspect fields. */
    @Test
    fun `leak result maps to alert payload`() {
        val sink = RecordingMemorySink()
        val reporter = MemoryReporter(MemoryConfig(), sink)

        reporter.onLeakFound(
            LeakResult(
                leakClass = "example.LeakingViewModel",
                type = LeakType.VIEW_MODEL,
                retainedCount = 2,
                scene = "Checkout",
                suspectFields = listOf("context", "view")
            )
        )

        val report = sink.reports.single()
        assertEquals("memory_leak", report.name)
        assertEquals("Checkout", report.scene)
        assertEquals("VIEW_MODEL", report.fields["leakType"])
        assertEquals(2, report.fields["retainedCount"])
        assertTrue((report.fields["suspectFields"] as String).contains("context"))
        assertFalse((report.fields["suspectFields"] as String).isBlank())
    }
}

/** In-memory sink used to assert classified reports without global APM state. */
internal class RecordingMemorySink : MemoryReportSink {
    /** Reports emitted during one test. */
    val reports = mutableListOf<MemoryReport>()

    /** Records one report in call order. */
    override fun emit(report: MemoryReport) {
        reports += report
    }
}
