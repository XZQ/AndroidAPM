package com.apm.remoteconfig

import com.apm.uploader.HttpHeaderProvider
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Performs one bounded authenticated GET against the Collector remote-config endpoint. */
internal interface RemoteConfigTransport {

    /** Fetches the current entity using [etag] for conditional validation. */
    fun fetch(etag: String?): RemoteConfigHttpResponse
}

/** HttpURLConnection transport with strict identity headers and bounded response consumption. */
internal class HttpRemoteConfigTransport(
    /** Immutable endpoint and installation identity. */
    private val config: RemoteConfigClientConfig,
    /** Per-request short-lived credential provider shared with the event uploader. */
    private val headerProvider: HttpHeaderProvider,
    /** Clock used when the server omits its Date header. */
    private val clock: RemoteConfigClock
) : RemoteConfigTransport {

    /** Executes one request and closes every response/error stream before disconnecting. */
    override fun fetch(etag: String?): RemoteConfigHttpResponse {
        var connection: HttpURLConnection? = null
        try {
            val dynamicHeaders = validateDynamicHeaders(headerProvider.currentHeaders())
            connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = METHOD_GET
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                useCaches = false
                doInput = true
                setRequestProperty(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                setRequestProperty(HEADER_APP_ID, config.appId)
                setRequestProperty(HEADER_ENVIRONMENT, config.environment)
                setRequestProperty(HEADER_INSTALLATION_ID, config.installationId)
                if (etag != null) {
                    setRequestProperty(HEADER_IF_NONE_MATCH, etag)
                }
                for ((name, value) in dynamicHeaders) {
                    setRequestProperty(name, value)
                }
            }
            val status = connection.responseCode
            val body = if (status == HTTP_OK) {
                readBounded(connection.inputStream, config.maxResponseBytes)
                    .toString(Charsets.UTF_8)
            } else {
                // Drain a bounded error body so keep-alive resources are not retained indefinitely.
                connection.errorStream?.use { stream -> readBounded(stream, config.maxResponseBytes) }
                null
            }
            return RemoteConfigHttpResponse(
                statusCode = status,
                etag = connection.getHeaderField(HEADER_ETAG),
                body = body,
                serverTimeMs = connection.getHeaderFieldDate(HEADER_DATE, clock.wallTimeMs())
            )
        } finally {
            connection?.disconnect()
        }
    }

    /** Validates dynamic credentials and prevents identity/conditional header replacement. */
    private fun validateDynamicHeaders(headers: Map<String, String>): Map<String, String> {
        for ((name, value) in headers) {
            require(name.isNotBlank()) { "Invalid remote config header name" }
            require(name.all { character ->
                character.code in VISIBLE_ASCII_MIN..VISIBLE_ASCII_MAX &&
                    character !in HEADER_NAME_SEPARATORS
            }) {
                "Invalid remote config header name"
            }
            require(value.all { character -> character == '\t' || character.code >= HEADER_VALUE_MIN }) {
                "Invalid remote config header value"
            }
            require(name.lowercase(Locale.US) !in RESERVED_HEADER_NAMES) {
                "Remote config identity header cannot be overridden"
            }
        }
        return headers
    }

    /** Reads a stream to EOF while rejecting responses that exceed [maxBytes]. */
    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        stream.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_RESPONSE_CAPACITY))
            val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) {
                    break
                }
                require(output.size() + count <= maxBytes) { "Remote config response is too large" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    companion object {
        /** HTTP request and status constants. */
        private const val METHOD_GET = "GET"
        private const val HTTP_OK = 200

        /** Header names shared with AndroidAPM-Server. */
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_APP_ID = "X-APM-App-Id"
        private const val HEADER_ENVIRONMENT = "X-APM-Environment"
        private const val HEADER_INSTALLATION_ID = "X-APM-Installation-Id"
        private const val HEADER_IF_NONE_MATCH = "If-None-Match"
        private const val HEADER_ETAG = "ETag"
        private const val HEADER_DATE = "Date"

        /** Expected response media type. */
        private const val CONTENT_TYPE_JSON = "application/json"

        /** Bounded response allocation constants. */
        private const val INITIAL_RESPONSE_CAPACITY = 8 * 1_024
        private const val RESPONSE_BUFFER_BYTES = 4 * 1_024

        /** Conservative header character bounds. */
        private const val VISIBLE_ASCII_MIN = 0x21
        private const val VISIBLE_ASCII_MAX = 0x7E
        private const val HEADER_VALUE_MIN = 0x20

        /** RFC separators excluded from the conservative header-name token subset. */
        private const val HEADER_NAME_SEPARATORS = "()<>@,;:\\\"/[]?={} \t"

        /** Headers owned by this transport and unavailable to credential providers. */
        private val RESERVED_HEADER_NAMES = setOf(
            HEADER_ACCEPT.lowercase(Locale.US),
            HEADER_APP_ID.lowercase(Locale.US),
            HEADER_ENVIRONMENT.lowercase(Locale.US),
            HEADER_INSTALLATION_ID.lowercase(Locale.US),
            HEADER_IF_NONE_MATCH.lowercase(Locale.US),
            "host",
            "connection",
            "content-length"
        )
    }
}
