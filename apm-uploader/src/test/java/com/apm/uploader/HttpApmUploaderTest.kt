package com.apm.uploader

import com.apm.model.ApmEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    }
}
