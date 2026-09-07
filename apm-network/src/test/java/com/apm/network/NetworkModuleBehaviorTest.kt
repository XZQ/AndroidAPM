package com.apm.network

import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol

/** Behavioral tests for request classification, aggregation, and phase reporting. */
class NetworkModuleBehaviorTest {
    /** A failed route followed by success must not contaminate either phase or summary outcome. */
    @Test
    fun `listener successful route retry reports final success`() {
        for (summary in listOf(false, true)) {
            val sink = RecordingNetworkSink()
            val module = NetworkModule(NetworkConfig(slowThresholdMs = 0), sink)
            module.onStart()
            val call = OkHttpClient().newCall(Request.Builder().url("https://example.test/retry").build())
            val listener = ApmEventListener(module, 0, summary)
            val address = InetSocketAddress("127.0.0.1", 443)
            listener.callStart(call)
            listener.connectStart(call, address, Proxy.NO_PROXY)
            listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("route failed"))
            listener.connectStart(call, address, Proxy.NO_PROXY)
            listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
            listener.responseHeadersEnd(call, Response.Builder().request(call.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").build())
            listener.callEnd(call)
            assertTrue(sink.reports.none { it.name == "network_error" })
            assertEquals(200, sink.reports.last().fields["statusCode"])
            assertEquals(if (summary) 1L else 0L, module.getStats().totalRequests)
        }
    }

    /** Reused connections and redirects produce one final summary, while terminal body failure wins. */
    @Test
    fun `listener follows final outcome for reuse redirects and body failure`() {
        for (bodyFailure in listOf(false, true)) {
            val sink = RecordingNetworkSink()
            val module = NetworkModule(NetworkConfig(slowThresholdMs = 0), sink)
            module.onStart()
            val call = OkHttpClient().newCall(Request.Builder().url("https://example.test/reused").build())
            val listener = ApmEventListener(module, 0, true)
            listener.callStart(call)
            for (code in listOf(302, 200)) {
                listener.responseHeadersEnd(call, Response.Builder().request(call.request())
                    .protocol(Protocol.HTTP_1_1).code(code).message("response").build())
            }
            listener.responseBodyStart(call)
            if (bodyFailure) listener.callFailed(call, IOException("body failed")) else listener.callEnd(call)
            val summaries = sink.reports.filter { it.name == "network_error" || it.name == "network_request" }
            assertEquals(1, summaries.size)
            assertEquals(if (bodyFailure) "network_error" else "network_request", summaries.single().name)
            assertEquals(if (bodyFailure) -1 else 200, sink.reports.last().fields["statusCode"])
        }
    }
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

    /** Explicit HttpURLConnection tracing executes once and preserves the host result. */
    @Test
    fun `http url connection trace reports successful response`() {
        val sink = RecordingNetworkSink()
        val clock = SequenceNanoClock(1_000_000L, 6_000_000L)
        val module = NetworkModule(NetworkConfig(), sink, clock::read)
        val connection = FakeHttpUrlConnection(statusCode = 204, contentLength = 12L).apply {
            requestMethod = "POST"
        }
        module.onStart()

        val result = module.traceHttpUrlConnection(connection, requestSize = 7L) { traced, code ->
            assertSame(connection, traced)
            assertEquals(204, code)
            "host-result"
        }

        assertEquals("host-result", result)
        assertEquals(1, connection.responseCodeReads)
        assertFalse(connection.disconnected)
        val report = sink.reports.single()
        assertEquals("network_request", report.name)
        assertEquals("https://example.test/resource", report.fields["url"])
        assertEquals("POST", report.fields["method"])
        assertEquals(204, report.fields["statusCode"])
        assertEquals(5L, report.fields["durationMs"])
        assertEquals(7L, report.fields["requestSize"])
        assertEquals(12L, report.fields["responseSize"])
    }

    /** HTTP error responses remain normal host results while producing an error-class event. */
    @Test
    fun `http url connection trace classifies http error without throwing`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(), sink)
        val connection = FakeHttpUrlConnection(statusCode = 503)
        module.onStart()

        val result = module.traceHttpUrlConnection(connection) { _, code -> code }

        assertEquals(503, result)
        assertEquals("network_error", sink.reports.single().name)
        assertEquals(503, sink.reports.single().fields["statusCode"])
        assertEquals(null, sink.reports.single().fields["error"])
    }

    /** Header and body IO failures are reported and the exact host exception is rethrown. */
    @Test
    fun `http url connection trace preserves transport exception`() {
        val sink = RecordingNetworkSink()
        val failure = IOException("offline")
        val module = NetworkModule(NetworkConfig(), sink)
        val connection = FakeHttpUrlConnection(responseFailure = failure)
        module.onStart()

        val actual = assertThrows(IOException::class.java) {
            module.traceHttpUrlConnection(connection) { _, _ -> "unreachable" }
        }

        assertSame(failure, actual)
        val report = sink.reports.single()
        assertEquals("network_error", report.name)
        assertEquals(-1, report.fields["statusCode"])
        assertEquals("offline", report.fields["error"])
    }

    /** An IO failure while the host consumes the body remains a transport error with exception identity. */
    @Test
    fun `http url connection trace reports body read failure`() {
        val sink = RecordingNetworkSink()
        val failure = IOException("body truncated")
        val module = NetworkModule(NetworkConfig(), sink)
        val connection = FakeHttpUrlConnection(statusCode = 200)
        module.onStart()

        val actual = assertThrows(IOException::class.java) {
            module.traceHttpUrlConnection(connection) { _, _ -> throw failure }
        }

        assertSame(failure, actual)
        val report = sink.reports.single()
        assertEquals("network_error", report.name)
        assertEquals(-1, report.fields["statusCode"])
        assertEquals("body truncated", report.fields["error"])
    }

    /** Host processing failures after headers keep the real HTTP outcome instead of becoming transport errors. */
    @Test
    fun `http url connection trace preserves host processing exception`() {
        val sink = RecordingNetworkSink()
        val failure = IllegalStateException("parse failed")
        val module = NetworkModule(NetworkConfig(), sink)
        val connection = FakeHttpUrlConnection(statusCode = 200)
        module.onStart()

        val actual = assertThrows(IllegalStateException::class.java) {
            module.traceHttpUrlConnection(connection) { _, _ -> throw failure }
        }

        assertSame(failure, actual)
        val report = sink.reports.single()
        assertEquals("network_request", report.name)
        assertEquals(200, report.fields["statusCode"])
        assertEquals(null, report.fields["error"])
    }

    /** Recoverable reporting failures cannot replace a successful host return value. */
    @Test
    fun `http url connection trace isolates report failure`() {
        val module = NetworkModule(NetworkConfig(), ThrowingNetworkSink())
        val connection = FakeHttpUrlConnection(statusCode = 200)
        module.onStart()

        val result = module.traceHttpUrlConnection(connection) { _, _ -> "preserved" }

        assertEquals("preserved", result)
    }

    /** A disabled module still executes the host request block but emits nothing. */
    @Test
    fun `stopped module trace executes without telemetry`() {
        val sink = RecordingNetworkSink()
        val module = NetworkModule(NetworkConfig(), sink)
        val connection = FakeHttpUrlConnection(statusCode = 200)

        val result = module.traceHttpUrlConnection(connection) { _, code -> code }

        assertEquals(200, result)
        assertTrue(sink.reports.isEmpty())
    }

    /** Fatal VM errors remain visible and are not converted into network events. */
    @Test
    fun `http url connection trace does not swallow fatal error`() {
        val sink = RecordingNetworkSink()
        val fatal = OutOfMemoryError("fatal")
        val module = NetworkModule(NetworkConfig(), sink)
        val connection = FakeHttpUrlConnection(statusCode = 200)
        module.onStart()

        val actual = assertThrows(OutOfMemoryError::class.java) {
            module.traceHttpUrlConnection(connection) { _, _ -> throw fatal }
        }

        assertSame(fatal, actual)
        assertTrue(sink.reports.isEmpty())
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

/** Sink that simulates a recoverable telemetry failure after the host request completes. */
internal class ThrowingNetworkSink : NetworkReportSink {
    /** Always fails so the explicit wrapper's no-throw report boundary can be verified. */
    override fun emit(report: NetworkReport) {
        throw IllegalStateException("sink unavailable")
    }
}

/** Deterministic monotonic clock that returns its configured values in order. */
internal class SequenceNanoClock(vararg values: Long) {
    /** Remaining values consumed by [read]. */
    private val remaining = ArrayDeque(values.toList())

    /** Returns the next configured timestamp. */
    fun read(): Long = remaining.removeFirst()
}

/** Minimal in-memory HttpURLConnection used to verify helper ownership and exception semantics. */
internal class FakeHttpUrlConnection(
    /** HTTP response returned after request execution. */
    private val statusCode: Int = 200,
    /** Header-derived response length. */
    private val contentLength: Long = 0L,
    /** Optional failure raised while receiving response headers. */
    private val responseFailure: IOException? = null
) : HttpURLConnection(URL("https://example.test/resource")) {
    /** Number of times the helper requested the response code. */
    var responseCodeReads: Int = 0
        private set

    /** Whether the helper incorrectly disconnected the host-owned connection. */
    var disconnected: Boolean = false
        private set

    /** No real socket is created by this test double. */
    override fun connect() {
        connected = true
    }

    /** Records explicit host disconnection without owning any real resource. */
    override fun disconnect() {
        disconnected = true
    }

    /** This deterministic test connection never uses a proxy. */
    override fun usingProxy(): Boolean = false

    /** Executes the synthetic request exactly once per caller access. */
    override fun getResponseCode(): Int {
        responseCodeReads += 1
        responseFailure?.let { throw it }
        connected = true
        return statusCode
    }

    /** Returns the configured Content-Length equivalent. */
    override fun getContentLengthLong(): Long = contentLength
}
