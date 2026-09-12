package com.apm.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real OkHttp/socket regressions for headers, body completion, transport failure and ownership. */
class OkHttpCompletionTest {
    /** All supported integration modes count truncated bodies as one final error. */
    @Test
    fun `truncated response is never counted as a header-time success`() {
        for (mode in Mode.entries) verifyResponse(mode, truncated = true, statusCode = HTTP_OK)
    }

    /** Body completion counts once with the listener alone, both components, or legacy interceptor. */
    @Test
    fun `successful streaming bodies are counted once after completion`() {
        for (mode in Mode.entries) verifyResponse(mode, truncated = false, statusCode = HTTP_OK)
    }

    /** An HTTP error is a completed call but still an error in request classification. */
    @Test
    fun `http error response has one final error summary`() {
        verifyResponse(Mode.COMBINED, truncated = false, statusCode = HTTP_UNAVAILABLE)
    }

    /** A pre-cancelled call may never enter an interceptor; the default listener still sees failure. */
    @Test
    fun `listener counts cancellation before interceptor entry`() {
        val module = NetworkModule().apply { onStart() }
        val client = OkHttpClient.Builder().eventListenerFactory(ApmEventListener.factory(module)).build()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1/cancelled").build())
            call.cancel()
            assertThrows(IOException::class.java) { call.execute() }
            assertEquals(1L, module.getStats().totalRequests)
            assertEquals(1L, module.getStats().errorCount)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            module.onStop()
        }
    }

    /** Holds the body behind a latch so the zero-summary assertion at headers is deterministic. */
    private fun verifyResponse(mode: Mode, truncated: Boolean, statusCode: Int) {
        val reports = CopyOnWriteArrayList<NetworkReport>()
        val module = NetworkModule(NetworkConfig(slowThresholdMs = 0), NetworkReportSink { reports += it })
        module.onStart()
        val builder = OkHttpClient.Builder()
        if (mode != Mode.LISTENER_ONLY) builder.addInterceptor(ApmNetworkInterceptor(module))
        if (mode != Mode.INTERCEPTOR_ONLY) {
            builder.eventListenerFactory(if (mode == Mode.PHASE_ONLY) {
                ApmEventListener.factory(module, 0, reportSummary = false)
            } else ApmEventListener.factory(module, 0))
        }
        val client = builder.build()
        val releaseBody = CountDownLatch(1)
        val serverError = AtomicReference<Throwable>()
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { server ->
            server.soTimeout = TIMEOUT_MS
            val responder = Thread({
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = TIMEOUT_MS
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers only. */ }
                        val length = if (truncated) TRUNCATED_DECLARED_LENGTH else BODY.length
                        socket.getOutputStream().write(("HTTP/1.1 $statusCode response\r\n" +
                            "Content-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
                        socket.getOutputStream().flush()
                        check(releaseBody.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                        socket.getOutputStream().write(BODY.toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (error: Throwable) { serverError.set(error) }
            }, SERVER_THREAD).apply { isDaemon = true; start() }
            try {
                val request = Request.Builder().url("http://$LOOPBACK:${server.localPort}/body").build()
                client.newCall(request).execute().use { response ->
                    assertEquals(0L, module.getStats().totalRequests)
                    releaseBody.countDown()
                    if (truncated) {
                        assertThrows(IOException::class.java) { response.body!!.string() }
                    } else assertEquals(BODY, response.body!!.string())
                }
                responder.join(TIMEOUT_MS.toLong())
                assertEquals(null, serverError.get())
                val stats = module.getStats()
                val failed = truncated || statusCode == HTTP_UNAVAILABLE
                assertEquals(1L, stats.totalRequests)
                assertEquals(if (failed) 1L else 0L, stats.errorCount)
                assertEquals(if (failed) 0L else 1L, stats.successCount)
                val summaries = reports.filter { it.name == "network_request" || it.name == "network_error" }
                assertEquals(1, summaries.size)
                assertTrue((summaries.single().fields["durationMs"] as Long) >= 0L)
                if (!truncated) assertEquals(BODY.length.toLong(), summaries.single().fields["responseSize"])
            } finally {
                releaseBody.countDown()
                server.close()
                responder.join(TIMEOUT_MS.toLong())
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                module.onStop()
            }
        }
    }

    /** Supported source-compatible integration combinations. */
    private enum class Mode { LISTENER_ONLY, COMBINED, INTERCEPTOR_ONLY, PHASE_ONLY }

    companion object {
        /** Loopback-only transport. */ private const val LOOPBACK = "127.0.0.1"
        /** Synthetic body. */ private const val BODY = "abc"
        /** Declared size deliberately exceeding the bytes sent. */ private const val TRUNCATED_DECLARED_LENGTH = 20
        /** Success response. */ private const val HTTP_OK = 200
        /** HTTP error response. */ private const val HTTP_UNAVAILABLE = 503
        /** Bounded socket/latch waits. */ private const val TIMEOUT_MS = 5_000
        /** Daemon fixture thread name. */ private const val SERVER_THREAD = "okhttp-completion-test"
    }
}
