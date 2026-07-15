package com.apm.network

import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral tests for request classification, aggregation, and phase reporting. */
class NetworkModuleBehaviorTest {
    /** A stopped module ignores host callbacks and preserves empty statistics. */
    @Test
    fun `stopped module ignores request callbacks`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(), sink)

        module.onRequestComplete("https://example.test", "GET", 200, 10)

        assertTrue(sink.reports.isEmpty())
        assertEquals(NetworkStats(), module.getStats())
    }

    /** Successful, slow, and failed requests produce truthful fields and cumulative statistics. */
    @Test
    fun `request outcomes update reports and statistics`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(slowThresholdMs = 100), sink)
        module.onStart()

        module.onRequestComplete("https://example.test/ok", "GET", 204, 80, 3, 7)
        module.onRequestComplete("https://example.test/fail", "POST", 503, 150, error = "offline")

        assertEquals(listOf("network_request", "network_error"), sink.reports.map(NetworkReport::name))
        assertEquals(ApmSeverity.WARN, sink.reports.last().severity)
        assertEquals(true, sink.reports.last().fields["isSlow"])
        assertEquals("offline", sink.reports.last().fields["error"])
        assertEquals(NetworkStats(2, 1, 1, 115, 150), module.getStats())
    }

    /** Reaching the configured window emits one aggregate with cumulative counters. */
    @Test
    fun `aggregate window emits summary`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(aggregateWindowSize = 2), sink)
        module.onStart()

        module.onRequestComplete("a", "GET", 200, 10)
        module.onRequestComplete("b", "GET", 500, 30)

        assertEquals(
            listOf("network_request", "network_error", "network_aggregate"),
            sink.reports.map(NetworkReport::name)
        )
        val aggregate = sink.reports.last().fields
        assertEquals(2L, aggregate["totalRequests"])
        assertEquals(20L, aggregate["avgDurationMs"])
        assertEquals(30L, aggregate["maxDurationMs"])
    }

    /** Phase details are suppressed for fast successes but retained for slow and failed requests. */
    @Test
    fun `phase reports honor threshold and error override`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(slowThresholdMs = 100), sink)
        module.onStart()

        module.onNetworkPhaseStats(NetworkRequestStats(url = "fast", totalMs = 99))
        module.onNetworkPhaseStats(NetworkRequestStats(url = "slow", totalMs = 100, dnsMs = 7))
        module.onNetworkPhaseStats(NetworkRequestStats(url = "failed", totalMs = 1, error = "timeout"))

        assertEquals(2, sink.reports.size)
        assertEquals(7L, sink.reports.first().fields["dnsMs"])
        assertEquals(ApmSeverity.WARN, sink.reports.last().severity)
    }

    /** Request and phase payload strings are bounded by the configured maximum. */
    @Test
    fun `payload strings are truncated`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(maxPayloadSize = 4, slowThresholdMs = 0), sink)
        module.onStart()

        module.onRequestComplete("abcdefgh", "GET", -1, 1, error = "abcdefgh")
        module.onNetworkPhaseStats(NetworkRequestStats(url = "abcdefgh", error = "abcdefgh"))

        assertEquals("abcd", sink.reports[0].fields["url"])
        assertEquals("abcd", sink.reports[0].fields["error"])
        assertEquals("abcd", sink.reports[1].fields["url"])
        assertEquals("abcd", sink.reports[1].fields["error"])
    }
}

/** In-memory network sink used to assert emitted reports without global APM state. */
internal class RecordingNetworkSink : NetworkReportSink {
    /** Reports captured in emission order. */
    val reports = mutableListOf<NetworkReport>()

    /** Captures one report for the owning test. */
    override fun emit(report: NetworkReport) {
        reports += report
    }
}
