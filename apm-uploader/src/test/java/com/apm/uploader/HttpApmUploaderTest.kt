package com.apm.uploader

import com.apm.model.ApmEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * Integration tests for HTTP batching and compression.
 */
class HttpApmUploaderTest {

    /** A gzip batch is transmitted as one request with a matching header. */
    @Test
    fun `gzip batch uses one request and can be decoded`() {
        ServerSocket(0).use { server ->
            val body = AtomicReference<String>()
            val encoding = AtomicReference<String>()
            val received = CountDownLatch(1)
            val serverThread = Thread {
                server.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val headers = readHeaders(input)
                    val length = headers["content-length"]?.toInt() ?: 0
                    val compressed = input.readNBytes(length)
                    encoding.set(headers["content-encoding"])
                    body.set(GZIPInputStream(compressed.inputStream()).bufferedReader().readText())
                    socket.getOutputStream().use { output ->
                        output.write(
                            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        output.flush()
                    }
                    received.countDown()
                }
            }.apply {
                name = "http-apm-uploader-test"
                start()
            }
            val uploader = HttpApmUploader(
                endpoint = "http://127.0.0.1:${server.localPort}/events",
                enableGzip = true
            )

            assertTrue(uploader.uploadBatch(listOf(event("first"), event("second"))))
            assertTrue(received.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("gzip", encoding.get())
            assertTrue(body.get().contains("name=first"))
            assertTrue(body.get().contains("name=second"))
            assertEquals(2, body.get().lineSequence().count())
            serverThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        }
    }

    /** 429 with Retry-After seconds surfaces a millisecond hint and fails the batch. */
    @Test
    fun `retry after seconds header is surfaced as hint`() {
        withRawHttpServer(
            listOf(RawResponse(HTTP_RATE_LIMITED, mapOf("Retry-After" to "7")))
        ) { uploader, _ ->
            assertFalse(uploader.uploadBatch(listOf(event("limited"))))
            assertEquals(7_000L, uploader.retryAfterHintMs())
        }
    }

    /** A successful upload clears any previous Retry-After hint. */
    @Test
    fun `successful upload clears retry after hint`() {
        withRawHttpServer(
            listOf(
                RawResponse(HTTP_RATE_LIMITED, mapOf("Retry-After" to "3")),
                RawResponse(HTTP_OK)
            )
        ) { uploader, _ ->
            // 第一次 429 记录提示
            assertFalse(uploader.uploadBatch(listOf(event("first"))))
            assertEquals(3_000L, uploader.retryAfterHintMs())
            // 第二次 200 清除提示
            assertTrue(uploader.uploadBatch(listOf(event("second"))))
            assertNull(uploader.retryAfterHintMs())
        }
    }

    /** Error responses with bodies are drained without breaking result mapping. */
    @Test
    fun `error response body is consumed and mapped to failure`() {
        withRawHttpServer(
            listOf(RawResponse(HTTP_SERVER_ERROR_CODE, body = "{\"error\":\"backend unavailable\"}"))
        ) { uploader, requestCount ->
            assertFalse(uploader.uploadBatch(listOf(event("failed"))))
            assertEquals(1, requestCount.get())
            assertNull(uploader.retryAfterHintMs())
        }
    }

    /** Dynamic headers are resolved for every request so token rotation takes effect immediately. */
    @Test
    fun `dynamic header provider refreshes authorization for every request`() {
        ServerSocket(0).use { server ->
            val authorizations = mutableListOf<String?>()
            val providerCalls = AtomicInteger(0)
            val serverThread = Thread {
                repeat(EXPECTED_DYNAMIC_REQUESTS) {
                    server.accept().use { socket ->
                        val input = BufferedInputStream(socket.getInputStream())
                        val requestHeaders = readHeaders(input)
                        val length = requestHeaders["content-length"]?.toInt() ?: 0
                        input.readNBytes(length)
                        authorizations += requestHeaders["authorization"]
                        socket.getOutputStream().use { output ->
                            output.write(SUCCESS_RESPONSE.toByteArray(Charsets.ISO_8859_1))
                            output.flush()
                        }
                    }
                }
            }.apply {
                name = "http-apm-dynamic-header-test"
                start()
            }
            val uploader = HttpApmUploader(
                endpoint = "http://127.0.0.1:${server.localPort}/events",
                headerProvider = HttpHeaderProvider {
                    mapOf("Authorization" to "Bearer token-${providerCalls.incrementAndGet()}")
                },
                logger = SILENT_LOGGER
            )

            assertTrue(uploader.uploadBatch(listOf(event("first"))))
            assertTrue(uploader.uploadBatch(listOf(event("second"))))
            serverThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))

            assertEquals(EXPECTED_DYNAMIC_REQUESTS, providerCalls.get())
            assertEquals(listOf("Bearer token-1", "Bearer token-2"), authorizations)
        }
    }

    /** Unsafe provider output fails the upload without sending the injected value. */
    @Test
    fun `dynamic header provider rejects response splitting`() {
        val uploader = HttpApmUploader(
            endpoint = "http://127.0.0.1:1/events",
            headerProvider = HttpHeaderProvider {
                mapOf("Authorization" to "Bearer valid\r\nX-Injected: value")
            },
            logger = SILENT_LOGGER
        )

        assertFalse(uploader.uploadBatch(listOf(event("unsafe"))))
    }

    /** Endpoint providers are evaluated per request and valid HTTPS rotation is accepted. */
    @Test
    fun `dynamic endpoint provider resolves secure destination for every request`() {
        val providerCalls = AtomicInteger(0)
        val uploader = HttpApmUploader(
            endpoint = "https://bootstrap.example/v1/events",
            endpointProvider = HttpEndpointProvider {
                providerCalls.incrementAndGet()
                "https://collector.example/v1/events"
            },
            logger = SILENT_LOGGER
        )
        val resolver = HttpApmUploader::class.java.getDeclaredMethod("resolveRequestEndpoint")
        resolver.isAccessible = true

        assertEquals("https://collector.example/v1/events", resolver.invoke(uploader))
        assertEquals("https://collector.example/v1/events", resolver.invoke(uploader))
        assertEquals(EXPECTED_DYNAMIC_REQUESTS, providerCalls.get())
    }

    /** Insecure remote endpoint values retain the application-bundled destination. */
    @Test
    fun `dynamic endpoint rejects insecure override`() {
        val bootstrap = "https://bootstrap.example/v1/events"
        val uploader = HttpApmUploader(
            endpoint = bootstrap,
            endpointProvider = HttpEndpointProvider { "http://collector.example/v1/events" },
            logger = SILENT_LOGGER
        )
        val resolver = HttpApmUploader::class.java.getDeclaredMethod("resolveRequestEndpoint")
        resolver.isAccessible = true

        assertEquals(bootstrap, resolver.invoke(uploader))
    }

    /**
     * 单个预置 HTTP 响应的描述。
     *
     * @property status 响应状态码
     * @property headers 额外响应头
     * @property body 响应体文本
     */
    private data class RawResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")

    /**
     * 启动一个按顺序回放预置响应的裸 socket HTTP 服务，运行测试体。
     * Android 单元测试的 boot classpath 没有 com.sun.net.httpserver，
     * 因此复用与 gzip 测试相同的 ServerSocket 方案。
     *
     * @param responses 依次回放的响应列表
     * @param block 测试体，接收 uploader 与已处理请求计数
     */
    private fun withRawHttpServer(responses: List<RawResponse>, block: (HttpApmUploader, AtomicInteger) -> Unit) {
        ServerSocket(0).use { server ->
            val requestCount = AtomicInteger(0)
            val serverThread = Thread {
                try {
                    // 依次处理每个预置响应对应的一次请求
                    for (response in responses) {
                        server.accept().use { socket ->
                            val input = BufferedInputStream(socket.getInputStream())
                            val headers = readHeaders(input)
                            // 读尽请求体，模拟真实服务端行为
                            val length = headers["content-length"]?.toInt() ?: 0
                            input.readNBytes(length)
                            requestCount.incrementAndGet()
                            val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
                            val head = buildString {
                                append("HTTP/1.1 ${response.status} Status\r\n")
                                for ((key, value) in response.headers) {
                                    append("$key: $value\r\n")
                                }
                                append("Content-Length: ${bodyBytes.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            socket.getOutputStream().let { output ->
                                output.write(head.toByteArray(Charsets.ISO_8859_1))
                                output.write(bodyBytes)
                                output.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 测试提前结束时 accept 抛出，忽略
                }
            }.apply {
                name = "http-apm-raw-server"
                start()
            }
            try {
                val uploader = HttpApmUploader(
                    endpoint = "http://127.0.0.1:${server.localPort}/events",
                    // JVM 单测没有 android.util.Log 实现，注入静默 logger
                    logger = SILENT_LOGGER
                )
                block(uploader, requestCount)
            } finally {
                serverThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            }
        }
    }

    /**
     * Reads HTTP request headers from a raw socket.
     *
     * @param input buffered socket input
     * @return lowercase header map
     */
    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headerBytes = ArrayList<Byte>()
        var matched = 0
        while (matched < HEADER_TERMINATOR.size) {
            val value = input.read()
            require(value >= 0) { "Unexpected end of HTTP headers" }
            val byte = value.toByte()
            headerBytes += byte
            matched = if (byte == HEADER_TERMINATOR[matched]) matched + 1 else 0
        }
        return headerBytes.toByteArray()
            .toString(Charsets.ISO_8859_1)
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).lowercase(Locale.US) to
                        line.substring(separator + 1).trim()
                }
            }
            .toMap()
    }

    /**
     * Creates a test event.
     *
     * @param name event name
     * @return event instance
     */
    private fun event(name: String): ApmEvent = ApmEvent(module = "test", name = name)

    companion object {
        /** CRLF sequence ending an HTTP header block. */
        private val HEADER_TERMINATOR = byteArrayOf(13, 10, 13, 10)

        /** Maximum wait for the local server. */
        private const val TEST_TIMEOUT_SECONDS = 5L

        /** HTTP 200 OK。 */
        private const val HTTP_OK = 200

        /** HTTP 429 Too Many Requests。 */
        private const val HTTP_RATE_LIMITED = 429

        /** HTTP 500 Internal Server Error。 */
        private const val HTTP_SERVER_ERROR_CODE = 500

        /** 静默日志实现：JVM 单测环境不可调用 android.util.Log。 */
        private val SILENT_LOGGER = object : UploaderLogger {
            override fun d(message: String) = Unit
            override fun w(message: String) = Unit
            override fun e(message: String, throwable: Throwable?) = Unit
        }

        /** Number of requests used to prove per-request provider evaluation. */
        private const val EXPECTED_DYNAMIC_REQUESTS = 2

        /** Minimal successful HTTP response returned by the dynamic-header test server. */
        private const val SUCCESS_RESPONSE =
            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
