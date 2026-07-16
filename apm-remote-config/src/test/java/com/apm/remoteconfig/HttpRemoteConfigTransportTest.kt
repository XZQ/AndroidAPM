package com.apm.remoteconfig

import com.apm.uploader.HttpHeaderProvider
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Socket-level tests for Collector identity, dynamic auth, ETag, and body limits. */
class HttpRemoteConfigTransportTest {

    /** One request carries required identity, a fresh bearer token, and the cached ETag. */
    @Test
    fun `transport sends collector identity auth and etag`() {
        ServerSocket(0).use { server ->
            val captured = AtomicReference<Map<String, String>>()
            val serverThread = Thread {
                server.accept().use { socket ->
                    captured.set(readHeaders(BufferedInputStream(socket.getInputStream())))
                    socket.getOutputStream().use { output ->
                        output.write(NOT_MODIFIED_RESPONSE.toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                    }
                }
            }.apply {
                name = "apm-remote-config-transport-test"
                start()
            }
            val transport = transport(server.localPort, MAX_RESPONSE_BYTES)

            val response = transport.fetch(CACHED_ETAG)
            serverThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))

            assertEquals(HTTP_NOT_MODIFIED, response.statusCode)
            assertEquals(CACHED_ETAG, response.etag)
            assertEquals("Bearer rotating-token", captured.get()["authorization"])
            assertEquals(TEST_APP_ID, captured.get()["x-apm-app-id"])
            assertEquals(TEST_ENVIRONMENT, captured.get()["x-apm-environment"])
            assertEquals(TEST_INSTALLATION_ID, captured.get()["x-apm-installation-id"])
            assertEquals(CACHED_ETAG, captured.get()["if-none-match"])
        }
    }

    /** A 200 body exceeding the configured budget fails before JSON parsing or caching. */
    @Test
    fun `transport rejects oversized response`() {
        ServerSocket(0).use { server ->
            val oversizedBody = "x".repeat(MIN_RESPONSE_BYTES + 1)
            val serverThread = Thread {
                server.accept().use { socket ->
                    readHeaders(BufferedInputStream(socket.getInputStream()))
                    val responseHead =
                        "HTTP/1.1 200 OK\r\nContent-Length: ${oversizedBody.length}\r\n" +
                            "Connection: close\r\n\r\n"
                    socket.getOutputStream().use { output ->
                        output.write(responseHead.toByteArray(Charsets.ISO_8859_1))
                        output.write(oversizedBody.toByteArray())
                        output.flush()
                    }
                }
            }.apply {
                name = "apm-remote-config-oversize-test"
                start()
            }
            val transport = transport(server.localPort, MIN_RESPONSE_BYTES)

            assertThrows(IllegalArgumentException::class.java) { transport.fetch(null) }
            serverThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        }
    }

    /** Creates a loopback transport with deterministic identity and dynamic auth. */
    private fun transport(port: Int, maxResponseBytes: Int): HttpRemoteConfigTransport {
        val config = RemoteConfigClientConfig(
            endpoint = "http://127.0.0.1:$port/v1/config",
            appId = TEST_APP_ID,
            environment = TEST_ENVIRONMENT,
            installationId = TEST_INSTALLATION_ID,
            refreshIntervalMs = TEST_REFRESH_INTERVAL_MS,
            maxResponseBytes = maxResponseBytes
        )
        return HttpRemoteConfigTransport(
            config = config,
            headerProvider = HttpHeaderProvider {
                mapOf("Authorization" to "Bearer rotating-token")
            },
            clock = object : RemoteConfigClock {
                override fun wallTimeMs(): Long = TEST_WALL_TIME_MS
                override fun elapsedRealtimeMs(): Long = 0L
            }
        )
    }

    /** Reads the raw request header block as lowercase names. */
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
        return headerBytes.toByteArray().toString(Charsets.ISO_8859_1)
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

    companion object {
        /** Collector identity fixture. */
        private const val TEST_APP_ID = "com.example.app"
        private const val TEST_ENVIRONMENT = "production"
        private const val TEST_INSTALLATION_ID = "anonymous-installation"

        /** HTTP and ETag fixture values. */
        private const val HTTP_NOT_MODIFIED = 304
        private const val CACHED_ETAG = "\"config-7\""
        private const val NOT_MODIFIED_RESPONSE =
            "HTTP/1.1 304 Not Modified\r\nETag: \"config-7\"\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"

        /** Configured bounds and test timeouts. */
        private const val MIN_RESPONSE_BYTES = 1_024
        private const val MAX_RESPONSE_BYTES = 256 * 1_024
        private const val TEST_REFRESH_INTERVAL_MS = 10_000L
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_WALL_TIME_MS = 1_700_000_000_000L

        /** CRLF sequence ending an HTTP header block. */
        private val HEADER_TERMINATOR = byteArrayOf(13, 10, 13, 10)
    }
}
