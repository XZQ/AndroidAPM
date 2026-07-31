package com.apm.uploader

import com.apm.model.ApmEvent
import com.apm.model.ApmBatchEnvelopeSerializer
import com.apm.model.ApmResourceContext
import com.apm.model.ApmWireProtocol
import com.apm.model.EncodedApmBatch
import com.apm.model.ProtobufSerializer
import com.apm.model.SerializationFormat
import com.apm.model.toLineProtocol
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * HTTP 上传实现。
 * 将 APM 事件通过 HTTP POST 发送到远端服务器。
 *
 * 支持能力：
 * - Line Protocol 格式批量上报
 * - 自定义 Headers（鉴权、设备信息等）
 * - 每请求动态 Headers（短期 Token、撤销与刷新）
 * - 连接超时/读取超时配置
 * - Gzip 压缩上传（可选）
 * - 自动重试（由 [RetryingApmUploader] 外层处理）
 *
 * 使用方式：
 * ```kotlin
 * Apm.init(this, ApmConfig(
 *     endpoint = "https://apm.example.com/api/v1/events",
 *     uploader = HttpApmUploader(
 *         endpoint = "https://apm.example.com/api/v1/events",
 *         headerProvider = HttpHeaderProvider {
 *             mapOf("Authorization" to "Bearer ${'$'}{tokenManager.currentToken()}")
 *         }
 *     )
 * ))
 * ```
 */
class HttpApmUploader(
    /** 上传目标地址（HTTP/HTTPS）。 */
    private val endpoint: String,
    /** 每次请求解析上传地址；远程覆盖仅接受 HTTPS。 */
    private val endpointProvider: HttpEndpointProvider = HttpEndpointProvider.DEFAULT,
    /** 自定义 HTTP Headers（如鉴权 Token、设备信息）。 */
    private val headers: Map<String, String> = emptyMap(),
    /** 每次请求动态获取的 Headers；同名项覆盖静态 [headers]。 */
    private val headerProvider: HttpHeaderProvider = HttpHeaderProvider.EMPTY,
    /** HTTP 连接超时（毫秒）。 */
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    /** HTTP 读取超时（毫秒）。 */
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    /** 是否启用 Gzip 压缩上传。 */
    private val enableGzip: Boolean = false,
    /** 事件序列化格式。 */
    private val serializationFormat: SerializationFormat = SerializationFormat.LINE_PROTOCOL,
    /** 日志输出，由宿主注入以尊重全局 debugLogging 开关。 */
    private val logger: UploaderLogger = UploaderLogger.DEFAULT,
    /** Standard resource identity embedded in versioned batch envelopes. */
    private val resourceContext: ApmResourceContext = ApmResourceContext(),
    /** Maximum uncompressed encoded bytes in one versioned HTTP request. */
    private val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES
) : BatchApmUploader {

    init {
        require(maxBatchBytes in 1..MAX_BATCH_BYTES) {
            "maxBatchBytes must be between 1 and $MAX_BATCH_BYTES"
        }
    }

    /**
     * 最近一次失败时服务端建议的重试延迟（毫秒）。
     * 来自 429/503 响应的 Retry-After 头；成功或无建议时为 null。
     */
    @Volatile
    private var lastRetryAfterMs: Long? = null

    /**
     * 返回服务端建议的下次重试延迟。
     *
     * @return Retry-After 换算的毫秒数，无建议时为 null
     */
    override fun retryAfterHintMs(): Long? = lastRetryAfterMs

    /**
     * 上传单条事件到远端服务器。
     * 根据 [serializationFormat] 选择 Line Protocol 或 Protobuf 编码。
     *
     * @param event 要上传的 APM 事件
     */
    /**
     * 批量上传多条事件。
     * 根据 [serializationFormat] 选择编码方式，一次性发送减少 HTTP 请求数。
     *
     * @param events 要上传的事件列表
     * @return true 表示完整批次上传成功
     */
    override fun uploadBatch(events: List<ApmEvent>): Boolean {
        if (events.isEmpty()) {
            return true
        }
        if (serializationFormat == SerializationFormat.PROTOBUF_ENVELOPE_V2) {
            return uploadVersionedBatches(events)
        }
        val payload = when (serializationFormat) {
            SerializationFormat.PROTOBUF -> ProtobufSerializer.serializeBatch(events)
            SerializationFormat.LINE_PROTOCOL -> {
                events.joinToString(separator = LINE_SEPARATOR) { it.toLineProtocol() }
                    .toByteArray(Charsets.UTF_8)
                }
            SerializationFormat.PROTOBUF_ENVELOPE_V2 -> error("Handled before legacy serialization")
        }
        return sendHttpPost(payload, ackExpectation = null)
    }

    /** Splits a versioned logical upload by encoded bytes and requires an exact ACK for each part. */
    private fun uploadVersionedBatches(events: List<ApmEvent>): Boolean {
        val encodedBatches = encodeWithinBudget(events) ?: return false
        for (batch in encodedBatches) {
            val accepted = sendHttpPost(
                payload = batch.payload,
                ackExpectation = AckExpectation(batch.batchId, batch.eventCount)
            )
            if (!accepted) {
                // Earlier parts may already be ACKed; at-least-once retry safely resends them by eventId.
                return false
            }
        }
        return true
    }

    /** Builds deterministic sub-batches whose uncompressed protobuf payload stays within budget. */
    private fun encodeWithinBudget(events: List<ApmEvent>): List<EncodedApmBatch>? {
        val encodedBatches = ArrayList<EncodedApmBatch>()
        val currentEvents = ArrayList<ApmEvent>()
        var currentEncoded: EncodedApmBatch? = null
        val sentAtMs = System.currentTimeMillis()
        for (event in events) {
            currentEvents += event
            val candidate = ApmBatchEnvelopeSerializer.serialize(currentEvents, resourceContext, sentAtMs)
            if (candidate.payload.size <= maxBatchBytes) {
                currentEncoded = candidate
                continue
            }
            // Remove the overflowing event and commit the preceding non-empty candidate.
            currentEvents.removeAt(currentEvents.lastIndex)
            if (currentEncoded == null) {
                logger.e("Versioned APM event exceeds maxBatchBytes=$maxBatchBytes")
                return null
            }
            encodedBatches += currentEncoded
            currentEvents.clear()
            currentEvents += event
            currentEncoded = ApmBatchEnvelopeSerializer.serialize(currentEvents, resourceContext, sentAtMs)
            if (currentEncoded.payload.size > maxBatchBytes) {
                logger.e("Versioned APM event exceeds maxBatchBytes=$maxBatchBytes")
                return null
            }
        }
        currentEncoded?.let(encodedBatches::add)
        return encodedBatches
    }

    /**
     * 发送 HTTP POST 请求。
     *
     * @param payload 请求体字节数组
     * @return true 表示服务端接受（HTTP 2xx），false 表示失败
     */
    private fun sendHttpPost(payload: ByteArray, ackExpectation: AckExpectation?): Boolean {
        var connection: HttpURLConnection? = null
        try {
            // Resolve credentials before opening the connection. Provider failures keep durable
            // rows pending instead of reusing a stale token retained by this uploader.
            val requestHeaders = resolveRequestHeaders()
            val requestEndpoint = resolveRequestEndpoint()
            // 建立 HTTP 连接
            val url = URL(requestEndpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = METHOD_POST
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // 根据序列化格式设置 Content-Type
                val contentType = when (serializationFormat) {
                    SerializationFormat.PROTOBUF -> CONTENT_TYPE_PROTOBUF
                    SerializationFormat.LINE_PROTOCOL -> CONTENT_TYPE_TEXT
                    SerializationFormat.PROTOBUF_ENVELOPE_V2 -> ApmWireProtocol.CONTENT_TYPE_ENVELOPE_V2
                }
                setRequestProperty(HEADER_CONTENT_TYPE, contentType)
                setRequestProperty(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                doOutput = true
                doInput = true
                useCaches = false
            }

            // 设置自定义 Headers（鉴权、设备信息等）
            for ((key, value) in requestHeaders) {
                connection.setRequestProperty(key, value)
            }
            if (ackExpectation != null) {
                // Protocol-owned headers let collectors reject unsupported schemas before parsing.
                connection.setRequestProperty(
                    ApmWireProtocol.HEADER_SCHEMA_VERSION,
                    ApmWireProtocol.ENVELOPE_SCHEMA_VERSION.toString()
                )
                connection.setRequestProperty(ApmWireProtocol.HEADER_SDK_VERSION, ApmWireProtocol.SDK_VERSION)
                connection.setRequestProperty(ApmWireProtocol.HEADER_BATCH_ID, ackExpectation.batchId)
                connection.setRequestProperty(
                    ApmWireProtocol.HEADER_EVENT_COUNT,
                    ackExpectation.eventCount.toString()
                )
            }
            // All headers must be set before outputStream establishes the connection.
            if (enableGzip) {
                connection.setRequestProperty(HEADER_CONTENT_ENCODING, ENCODING_GZIP)
            }

            // 写入请求体（可选 Gzip 压缩）
            val outputStream: OutputStream = connection.outputStream
            if (enableGzip) {
                // Gzip 压缩模式
                val gzipStream = GZIPOutputStream(outputStream)
                gzipStream.use { gos ->
                    gos.write(payload)
                    gos.finish()
                }
            } else {
                // 直接写入
                outputStream.use { os ->
                    os.write(payload)
                    os.flush()
                }
            }

            // 读取响应码
            val responseCode = connection.responseCode

            // 读尽并关闭响应流：不排空响应体会阻止 HttpURLConnection
            // 归还底层连接（keep-alive 失效），错误路径还可能泄漏连接
            drainResponse(connection, responseCode)

            // 限流/服务不可用时解析服务端建议的重试延迟，其余情况清除旧提示
            lastRetryAfterMs = if (
                responseCode == HTTP_TOO_MANY_REQUESTS ||
                responseCode == HTTP_SERVICE_UNAVAILABLE
            ) {
                parseRetryAfterMs(connection)
            } else {
                null
            }

            return when {
                // 成功：2xx
                responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> {
                    if (ackExpectation == null || hasMatchingAck(connection, ackExpectation)) {
                        true
                    } else {
                        logger.w("Versioned batch received 2xx without an exact whole-batch ACK")
                        false
                    }
                }
                // 限流：429
                responseCode == HTTP_TOO_MANY_REQUESTS -> {
                    logger.w("Server rate limited: $responseCode retryAfterMs=$lastRetryAfterMs")
                    false
                }
                // 服务端错误：5xx
                responseCode >= HTTP_SERVER_ERROR -> {
                    logger.w("Server error: $responseCode")
                    false
                }
                // 其他错误
                else -> {
                    logger.w("Upload failed: HTTP $responseCode")
                    false
                }
            }
        } catch (e: Exception) {
            // 网络异常（DNS、连接超时、读取超时等）：
            // 交换未完成，此时断开连接释放底层 socket
            logger.e("HTTP upload error: ${e.message}")
            connection?.disconnect()
            return false
        }
        // 正常完成的交换不调用 disconnect()，让底层连接回到 keep-alive 池复用
    }

    /** Returns true only when the collector echoes the exact schema, batch identity, and event count. */
    private fun hasMatchingAck(connection: HttpURLConnection, expectation: AckExpectation): Boolean {
        val schema = parseCanonicalPositiveInt(
            connection.getHeaderField(ApmWireProtocol.HEADER_SCHEMA_VERSION)
        )
        val batchId = connection.getHeaderField(ApmWireProtocol.HEADER_BATCH_ID)
        val eventCount = parseCanonicalPositiveInt(
            connection.getHeaderField(ApmWireProtocol.HEADER_EVENT_COUNT)
        )
        return schema == ApmWireProtocol.ENVELOPE_SCHEMA_VERSION &&
            batchId == expectation.batchId &&
            eventCount == expectation.eventCount
    }

    /** Parses an ACK integer only from its canonical unsigned decimal representation. */
    private fun parseCanonicalPositiveInt(value: String?): Int? {
        if (value.isNullOrEmpty() || value.any { it !in '0'..'9' }) return null
        if (value.length > 1 && value.first() == '0') return null
        return value.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Merges static and per-request headers and rejects unsafe transport overrides.
     *
     * @return validated header snapshot for one request
     */
    private fun resolveRequestHeaders(): Map<String, String> {
        val merged = LinkedHashMap<String, String>(headers.size + DEFAULT_DYNAMIC_HEADER_CAPACITY)
        merged.putAll(headers)
        // A fresh snapshot on every call allows token rotation without rebuilding the SDK.
        merged.putAll(headerProvider.currentHeaders())
        for ((name, value) in merged) {
            require(isValidHeaderName(name)) { "Invalid HTTP header name" }
            require(isValidHeaderValue(value)) { "Invalid HTTP header value" }
            require(name.lowercase(Locale.US) !in RESERVED_HEADER_NAMES) {
                "Transport header cannot be overridden: $name"
            }
        }
        return merged
    }

    /**
     * Resolves one upload destination and falls back to the bundled endpoint on unsafe overrides.
     *
     * Remote endpoint rotation is deliberately stricter than the bootstrap endpoint: only HTTPS
     * URLs with a host and without embedded credentials are accepted. Provider failures also retain
     * the bootstrap endpoint so a control-plane outage cannot disable telemetry delivery.
     */
    private fun resolveRequestEndpoint(): String {
        val candidate = try {
            endpointProvider.currentEndpoint(endpoint).trim()
        } catch (error: Exception) {
            logger.w("HTTP endpoint provider failed; retaining bootstrap endpoint")
            return endpoint
        }
        if (candidate == endpoint) {
            return endpoint
        }
        val parsed = try {
            URL(candidate)
        } catch (error: Exception) {
            logger.w("Rejected invalid remote HTTP endpoint; retaining bootstrap endpoint")
            return endpoint
        }
        if (parsed.protocol != HTTPS_SCHEME || parsed.host.isNullOrBlank() || parsed.userInfo != null) {
            logger.w("Rejected unsafe remote HTTP endpoint; retaining bootstrap endpoint")
            return endpoint
        }
        return parsed.toExternalForm()
    }

    /** Returns whether a header name is a conservative RFC token accepted by HttpURLConnection. */
    private fun isValidHeaderName(name: String): Boolean {
        return name.isNotEmpty() && name.all { character ->
            character.code in HEADER_VISIBLE_ASCII_MIN..HEADER_VISIBLE_ASCII_MAX &&
                character !in HEADER_NAME_SEPARATORS
        }
    }

    /** Returns whether a header value is free of CR/LF and other control characters. */
    private fun isValidHeaderValue(value: String): Boolean {
        return value.all { character ->
            character == '\t' || character.code >= HEADER_VALUE_VISIBLE_MIN
        }
    }

    /**
     * 读尽并关闭 HTTP 响应流。
     *
     * 成功响应读 inputStream，错误响应读 errorStream；
     * 排空失败不影响上传结果判定。
     *
     * @param connection 当前 HTTP 连接
     * @param responseCode 已读取的响应码
     */
    private fun drainResponse(connection: HttpURLConnection, responseCode: Int) {
        try {
            // 4xx/5xx 的响应体在 errorStream 中，2xx/3xx 在 inputStream 中
            val stream: InputStream? = if (responseCode >= HTTP_CLIENT_ERROR) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            stream?.use { input ->
                val buffer = ByteArray(DRAIN_BUFFER_SIZE)
                // 循环读取直到 EOF，内容直接丢弃，只为让连接可复用
                while (input.read(buffer) != -1) {
                    // 丢弃响应内容
                }
            }
        } catch (_: Exception) {
            // 排空失败（流已关闭/网络中断）不影响上传结果
        }
    }

    /**
     * 解析 Retry-After 响应头为毫秒延迟。
     *
     * 支持两种格式：秒数（如 "120"）与 HTTP-date（绝对时间）。
     *
     * @param connection 当前 HTTP 连接
     * @return 建议延迟毫秒数，无法解析时为 null
     */
    private fun parseRetryAfterMs(connection: HttpURLConnection): Long? {
        val headerValue = connection.getHeaderField(HEADER_RETRY_AFTER) ?: return null
        // 优先按秒数解析
        headerValue.trim().toLongOrNull()?.let { seconds ->
            if (seconds <= 0L) return 0L
            return if (seconds > Long.MAX_VALUE / MILLIS_PER_SECOND) {
                Long.MAX_VALUE
            } else {
                seconds * MILLIS_PER_SECOND
            }
        }
        // 回退按 HTTP-date 解析为绝对时间
        val dateMs = connection.getHeaderFieldDate(HEADER_RETRY_AFTER, 0L)
        if (dateMs > 0L) {
            val delta = dateMs - System.currentTimeMillis()
            // 过去的时间视为无建议
            return if (delta > 0L) delta else null
        }
        return null
    }

    companion object {
        /** HTTP 方法：POST。 */
        private const val METHOD_POST = "POST"

        /** Header: Content-Type。 */
        private const val HEADER_CONTENT_TYPE = "Content-Type"

        /** Header: Accept。 */
        private const val HEADER_ACCEPT = "Accept"

        /** Header: Content-Encoding。 */
        private const val HEADER_CONTENT_ENCODING = "Content-Encoding"

        /** Content-Type: 纯文本（Line Protocol）。 */
        private const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"

        /** Content-Type: Protobuf 二进制。 */
        private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"

        /** Content-Type: JSON。 */
        private const val CONTENT_TYPE_JSON = "application/json"

        /** 编码：Gzip。 */
        private const val ENCODING_GZIP = "gzip"

        /** 默认连接超时：10 秒。 */
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000

        /** 默认读取超时：15 秒。 */
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000

        /** Default uncompressed request-body budget for versioned batch envelopes. */
        const val DEFAULT_MAX_BATCH_BYTES = 1024 * 1024

        /** Absolute constructor bound preventing unreviewed multi-megabyte request buffers. */
        const val MAX_BATCH_BYTES = 4 * 1024 * 1024

        /** HTTP 成功状态码下限。 */
        private const val HTTP_SUCCESS_MIN = 200

        /** HTTP 成功状态码上限。 */
        private const val HTTP_SUCCESS_MAX = 299

        /** HTTP 限流状态码。 */
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** HTTP 服务不可用状态码（可能携带 Retry-After）。 */
        private const val HTTP_SERVICE_UNAVAILABLE = 503

        /** HTTP 客户端错误状态码起始（errorStream 生效边界）。 */
        private const val HTTP_CLIENT_ERROR = 400

        /** HTTP 服务端错误状态码起始。 */
        private const val HTTP_SERVER_ERROR = 500

        /** Header: Retry-After。 */
        private const val HEADER_RETRY_AFTER = "Retry-After"

        /** 响应排空缓冲区大小（字节）。 */
        private const val DRAIN_BUFFER_SIZE = 4096

        /** 每秒毫秒数。 */
        private const val MILLIS_PER_SECOND = 1000L

        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"

        /** Small initial capacity added for a typical Authorization-only provider. */
        private const val DEFAULT_DYNAMIC_HEADER_CAPACITY = 2

        /** Only secure transport is accepted for a remotely supplied upload endpoint. */
        private const val HTTPS_SCHEME = "https"

        /** Lowest visible ASCII code allowed in a header name. */
        private const val HEADER_VISIBLE_ASCII_MIN = 0x21

        /** Highest visible ASCII code allowed in a header name. */
        private const val HEADER_VISIBLE_ASCII_MAX = 0x7E

        /** Lowest non-control code allowed in a header value. */
        private const val HEADER_VALUE_VISIBLE_MIN = 0x20

        /** RFC separators excluded from the conservative header-name token subset. */
        private const val HEADER_NAME_SEPARATORS = "()<>@,;:\\\"/[]?={} \t"

        /** Transport-owned headers whose semantics must match the encoded request body. */
        private val RESERVED_HEADER_NAMES = setOf(
            HEADER_CONTENT_TYPE.lowercase(Locale.US),
            HEADER_CONTENT_ENCODING.lowercase(Locale.US),
            HEADER_ACCEPT.lowercase(Locale.US),
            ApmWireProtocol.HEADER_SCHEMA_VERSION.lowercase(Locale.US),
            ApmWireProtocol.HEADER_SDK_VERSION.lowercase(Locale.US),
            ApmWireProtocol.HEADER_BATCH_ID.lowercase(Locale.US),
            ApmWireProtocol.HEADER_EVENT_COUNT.lowercase(Locale.US),
            "content-length",
            "transfer-encoding",
            "connection",
            "host"
        )
    }

    /** Expected collector acknowledgement for one physical versioned request. */
    private data class AckExpectation(
        /** Stable batch identity sent in both body and request header. */
        val batchId: String,
        /** Complete event count required in the response ACK. */
        val eventCount: Int
    )
}
