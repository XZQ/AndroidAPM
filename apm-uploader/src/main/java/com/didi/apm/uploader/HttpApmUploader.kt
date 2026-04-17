package com.didi.apm.uploader

import android.util.Log
import com.didi.apm.model.ApmEvent
import com.didi.apm.model.toLineProtocol
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * HTTP 上传实现。
 * 将 APM 事件通过 HTTP POST 发送到远端服务器。
 *
 * 支持能力：
 * - Line Protocol 格式批量上报
 * - 自定义 Headers（鉴权、设备信息等）
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
 *         headers = mapOf("Authorization" to "Bearer xxx")
 *     )
 * ))
 * ```
 */
class HttpApmUploader(
    /** 上传目标地址（HTTP/HTTPS）。 */
    private val endpoint: String,
    /** 自定义 HTTP Headers（如鉴权 Token、设备信息）。 */
    private val headers: Map<String, String> = emptyMap(),
    /** HTTP 连接超时（毫秒）。 */
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    /** HTTP 读取超时（毫秒）。 */
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    /** 是否启用 Gzip 压缩上传。 */
    private val enableGzip: Boolean = false
) : ApmUploader {

    /**
     * 上传单条事件到远端服务器。
     * 将事件序列化为 Line Protocol 格式，通过 HTTP POST 发送。
     *
     * @param event 要上传的 APM 事件
     */
    override fun upload(event: ApmEvent) {
        val payload = event.toLineProtocol().toByteArray(Charsets.UTF_8)
        sendHttpPost(payload)
    }

    /**
     * 批量上传多条事件。
     * 将多条事件拼接为 Line Protocol 格式一次性发送，减少 HTTP 请求数。
     *
     * @param events 要上传的事件列表
     * @return 上传成功的事件数量
     */
    fun batchUpload(events: List<ApmEvent>): Int {
        if (events.isEmpty()) return 0
        // 每条事件一行，拼接为批量 payload
        val payload = events.joinToString(separator = LINE_SEPARATOR) { it.toLineProtocol() }
            .toByteArray(Charsets.UTF_8)
        return if (sendHttpPost(payload)) events.size else 0
    }

    /**
     * 发送 HTTP POST 请求。
     *
     * @param payload 请求体字节数组
     * @return true 表示服务端接受（HTTP 2xx），false 表示失败
     */
    private fun sendHttpPost(payload: ByteArray): Boolean {
        var connection: HttpURLConnection? = null
        try {
            // 建立 HTTP 连接
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = METHOD_POST
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // 请求头
                setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_TEXT)
                setRequestProperty(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                doOutput = true
                doInput = true
                useCaches = false
            }

            // 设置自定义 Headers（鉴权、设备信息等）
            for ((key, value) in headers) {
                connection.setRequestProperty(key, value)
            }

            // 写入请求体（可选 Gzip 压缩）
            val outputStream: OutputStream = connection.outputStream
            if (enableGzip) {
                // Gzip 压缩模式
                connection.setRequestProperty(HEADER_CONTENT_ENCODING, ENCODING_GZIP)
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
            return when {
                // 成功：2xx
                responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> {
                    true
                }
                // 限流：429
                responseCode == HTTP_TOO_MANY_REQUESTS -> {
                    Log.w(TAG, "Server rate limited: $responseCode")
                    false
                }
                // 服务端错误：5xx
                responseCode >= HTTP_SERVER_ERROR -> {
                    Log.w(TAG, "Server error: $responseCode")
                    false
                }
                // 其他错误
                else -> {
                    Log.w(TAG, "Upload failed: HTTP $responseCode")
                    false
                }
            }
        } catch (e: Exception) {
            // 网络异常（DNS、连接超时、读取超时等）
            Log.e(TAG, "HTTP upload error: ${e.message}")
            return false
        } finally {
            // 断开连接
            connection?.disconnect()
        }
    }

    companion object {
        /** Logcat Tag。 */
        private const val TAG = "HttpApmUploader"

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

        /** Content-Type: JSON。 */
        private const val CONTENT_TYPE_JSON = "application/json"

        /** 编码：Gzip。 */
        private const val ENCODING_GZIP = "gzip"

        /** 默认连接超时：10 秒。 */
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000

        /** 默认读取超时：15 秒。 */
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000

        /** HTTP 成功状态码下限。 */
        private const val HTTP_SUCCESS_MIN = 200

        /** HTTP 成功状态码上限。 */
        private const val HTTP_SUCCESS_MAX = 299

        /** HTTP 限流状态码。 */
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** HTTP 服务端错误状态码起始。 */
        private const val HTTP_SERVER_ERROR = 500

        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
    }
}
