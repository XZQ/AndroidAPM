package com.apm.network

import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong

/**
 * 网络请求监控模块。
 * 提供回调接口 [onRequestComplete]，由外部 HTTP 客户端（OkHttp Interceptor 等）调用。
 * 记录每条请求的 URL、方法、状态码、耗时，并定期发送聚合统计。
 *
 * 使用方式：
 * ```kotlin
 * // 在 OkHttp Interceptor 中
 * val start = System.nanoTime()
 * try {
 *     val response = chain.proceed(request)
 *     networkModule.onRequestComplete(
 *         url = request.url.toString(),
 *         method = request.method,
 *         statusCode = response.code,
 *         durationMs = (System.nanoTime() - start) / 1_000_000
 *     )
 *     return response
 * } catch (e: Exception) {
 *     networkModule.onRequestComplete(
 *         url = request.url.toString(),
 *         method = request.method,
 *         statusCode = -1,
 *         durationMs = (System.nanoTime() - start) / 1_000_000,
 *         error = e.message
 *     )
 *     throw e
 * }
 * ```
 */
class NetworkModule internal constructor(
    /** Immutable behavior configuration for this module instance. */
    private val config: NetworkConfig,
    /** Event destination, replaceable in JVM behavior tests. */
    private val reportSink: NetworkReportSink,
    /** Monotonic nanosecond clock used by explicit request tracing. */
    private val monotonicNanos: () -> Long = System::nanoTime
) : ApmModule {

    /** Creates a network module that reports through the process-wide APM runtime. */
    constructor(config: NetworkConfig = NetworkConfig()) : this(config, ApmNetworkReportSink)

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 是否已启动。 */
    @Volatile private var started = false

    // --- 聚合统计计数器 ---
    /** 总请求数。 */
    private val totalRequests = AtomicLong(0)
    /** 成功请求数。 */
    private val successCount = AtomicLong(0)
    /** 失败请求数。 */
    private val errorCount = AtomicLong(0)
    /** 累计耗时（毫秒），用于计算平均值。 */
    private val totalDurationMs = AtomicLong(0)
    /** 最大单次耗时（毫秒）。 */
    private val maxDurationMs = AtomicLong(0)
    /** 最近请求耗时队列，用于触发聚合。 */
    private val aggregateWindowCount = AtomicLong(0)

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableNetworkMonitor
        apmContext?.logger?.d("Network module started")
    }

    override fun onStop() {
        started = false
    }

    /**
     * Executes and traces one explicitly configured [HttpURLConnection].
     *
     * The host must configure method, headers, timeouts, redirect behavior, and request body before
     * calling this method. The helper reads [HttpURLConnection.getResponseCode] exactly once to
     * execute the request, then passes the received status to [block]. The block should consume the
     * response body when total body-read time must be included. This method never disconnects the
     * connection and never consumes response content on the host's behalf.
     *
     * Host return values and exceptions are preserved. An [IOException] while receiving headers or
     * consuming the body is reported as a network error. A non-I/O exception thrown by [block] after
     * headers were received preserves the HTTP outcome rather than being mislabeled as a transport
     * failure. Recoverable telemetry failures are isolated and recorded as SDK internal errors.
     *
     * @param connection configured connection whose request should be executed
     * @param requestSize known request-body size in bytes, or zero when unknown
     * @param block host response handling, receiving the connection and HTTP status code
     * @return the unmodified host result from [block]
     */
    @Throws(IOException::class)
    fun <T> traceHttpUrlConnection(
        connection: HttpURLConnection,
        requestSize: Long = 0L,
        block: (HttpURLConnection, Int) -> T
    ): T {
        // A disabled module preserves helper execution semantics without paying telemetry costs.
        if (!started) {
            val statusCode = connection.responseCode
            return block(connection, statusCode)
        }

        val startedAtNanos = monotonicNanos()
        val url = readConnectionUrlSafely(connection)
        val method = readRequestMethodSafely(connection)
        var statusCode = STATUS_CODE_NETWORK_ERROR
        var responseSize = 0L
        try {
            // Reading responseCode is the explicit request execution point owned by this helper.
            statusCode = connection.responseCode
            responseSize = readResponseSizeSafely(connection)
            val result = block(connection, statusCode)
            reportHttpUrlConnectionSafely(
                url = url,
                method = method,
                statusCode = statusCode,
                durationMs = elapsedMillis(startedAtNanos),
                requestSize = requestSize,
                responseSize = responseSize,
                error = null
            )
            return result
        } catch (error: Exception) {
            // Body-read IO failures are transport failures; host processing failures keep the HTTP result.
            val isTransportFailure = error is IOException || statusCode == STATUS_CODE_NETWORK_ERROR
            reportHttpUrlConnectionSafely(
                url = url,
                method = method,
                statusCode = if (isTransportFailure) STATUS_CODE_NETWORK_ERROR else statusCode,
                durationMs = elapsedMillis(startedAtNanos),
                requestSize = requestSize,
                responseSize = responseSize,
                error = if (isTransportFailure) describeError(error) else null
            )
            throw error
        }
    }

    /**
     * 一次网络请求完成时调用。
     * 由外部 HTTP 客户端在请求完成后回调。
     *
     * @param url 请求 URL
     * @param method HTTP 方法（GET/POST 等）
     * @param statusCode 响应状态码，-1 表示网络错误
     * @param durationMs 请求耗时（毫秒）
     * @param requestSize 请求体大小（字节），可选
     * @param responseSize 响应体大小（字节），可选
     * @param error 错误信息，非 null 表示请求失败
     */
    fun onRequestComplete(
        url: String,
        method: String,
        statusCode: Int,
        durationMs: Long,
        requestSize: Long = 0,
        responseSize: Long = 0,
        error: String? = null
    ) {
        if (!started) {
            return
        }

        // 更新聚合统计
        totalRequests.incrementAndGet()
        totalDurationMs.addAndGet(durationMs)

        // CAS 更新最大耗时
        maxDurationMs.accumulateAndGet(durationMs, ::maxOf)

        // 判断请求是否成功
        val isSuccess = statusCode in STATUS_CODE_SUCCESS_START..STATUS_CODE_SUCCESS_END && error == null
        if (isSuccess) {
            successCount.incrementAndGet()
        } else {
            errorCount.incrementAndGet()
        }

        // 构造事件字段
        val truncatedUrl = url.take(config.maxPayloadSize)
        val eventName = if (isSuccess) EVENT_NETWORK_REQUEST else EVENT_NETWORK_ERROR
        val severity = if (isSuccess) ApmSeverity.INFO else ApmSeverity.WARN

        val fields = mutableMapOf<String, Any?>(
            FIELD_URL to truncatedUrl,
            FIELD_METHOD to method,
            FIELD_STATUS_CODE to statusCode,
            FIELD_DURATION_MS to durationMs,
            FIELD_REQUEST_SIZE to requestSize,
            FIELD_RESPONSE_SIZE to responseSize
        )
        // 附加错误信息
        if (error != null) {
            fields[FIELD_ERROR] = error.take(config.maxPayloadSize)
        }
        // 标记慢请求
        if (durationMs >= config.slowThresholdMs) {
            fields[FIELD_IS_SLOW] = true
        }

        reportSink.emit(NetworkReport(
            name = eventName,
            kind = ApmEventKind.METRIC,
            severity = severity,
            fields = fields
        ))

        // 累计到聚合窗口
        val windowCount = aggregateWindowCount.incrementAndGet()
        if (windowCount >= config.aggregateWindowSize &&
            aggregateWindowCount.compareAndSet(windowCount, 0)
        ) {
            emitAggregate()
        }
    }

    /** 发送聚合统计事件并重置窗口。 */
    private fun emitAggregate() {
        val stats = getStats()
        reportSink.emit(NetworkReport(
            name = EVENT_NETWORK_AGGREGATE,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO, priority = ApmPriority.NORMAL,
            fields = mapOf(
                FIELD_TOTAL_REQUESTS to stats.totalRequests,
                FIELD_SUCCESS_COUNT to stats.successCount,
                FIELD_ERROR_COUNT to stats.errorCount,
                FIELD_AVG_DURATION_MS to stats.avgDurationMs,
                FIELD_MAX_DURATION_MS to stats.maxDurationMs
            )
        ))
    }

    /**
     * 接收 ApmEventListener 的分阶段网络统计数据。
     * 上报 DNS/TCP/TLS 各阶段耗时明细。
     *
     * @param stats 分阶段网络统计数据
     */
    fun onNetworkPhaseStats(stats: NetworkRequestStats) {
        if (!started) {
            return
        }
        // 只上报慢请求的分阶段数据，避免数据量过大
        if (stats.totalMs < config.slowThresholdMs && stats.error == null) {
            return
        }

        val fields = mutableMapOf<String, Any?>(
            FIELD_URL to stats.url.take(config.maxPayloadSize),
            FIELD_METHOD to stats.method,
            FIELD_STATUS_CODE to stats.statusCode,
            FIELD_DURATION_MS to stats.totalMs,
            FIELD_DNS_MS to stats.dnsMs,
            FIELD_TCP_MS to stats.tcpMs,
            FIELD_TLS_MS to stats.tlsMs,
            FIELD_REQUEST_HEADER_MS to stats.requestHeaderMs,
            FIELD_RESPONSE_HEADER_MS to stats.responseHeaderMs,
            FIELD_RESPONSE_BODY_MS to stats.responseBodyMs
        )
        if (stats.error != null) {
            fields[FIELD_ERROR] = stats.error.take(config.maxPayloadSize)
        }
        reportSink.emit(NetworkReport(
            name = EVENT_NETWORK_PHASE,
            kind = ApmEventKind.METRIC,
            severity = if (stats.error != null) ApmSeverity.WARN else ApmSeverity.INFO,
            fields = fields
        ))
    }

    /** 获取当前聚合统计数据。 */
    fun getStats(): NetworkStats {
        val total = totalRequests.get()
        val avg = if (total > 0) totalDurationMs.get() / total else 0L
        return NetworkStats(
            totalRequests = total,
            successCount = successCount.get(),
            errorCount = errorCount.get(),
            avgDurationMs = avg,
            maxDurationMs = maxDurationMs.get()
        )
    }

    /** Reads the configured URL without allowing custom connection metadata failures to escape. */
    private fun readConnectionUrlSafely(connection: HttpURLConnection): String {
        return try {
            connection.url?.toExternalForm().orEmpty()
        } catch (error: Exception) {
            // Metadata is optional telemetry and cannot prevent the host request from executing.
            Apm.recordInternalError(ERROR_TAG_HTTP_URL_CONNECTION_METADATA, error)
            ""
        }
    }

    /** Reads the configured method without allowing custom connection metadata failures to escape. */
    private fun readRequestMethodSafely(connection: HttpURLConnection): String {
        return try {
            connection.requestMethod ?: HTTP_METHOD_UNKNOWN
        } catch (error: Exception) {
            // Preserve host behavior even for a non-standard HttpURLConnection implementation.
            Apm.recordInternalError(ERROR_TAG_HTTP_URL_CONNECTION_METADATA, error)
            HTTP_METHOD_UNKNOWN
        }
    }

    /** Reads header-derived response size and normalizes unknown negative lengths to zero. */
    private fun readResponseSizeSafely(connection: HttpURLConnection): Long {
        return try {
            connection.contentLengthLong.coerceAtLeast(0L)
        } catch (error: Exception) {
            // Response length is diagnostic metadata; losing it must not alter response handling.
            Apm.recordInternalError(ERROR_TAG_HTTP_URL_CONNECTION_METADATA, error)
            0L
        }
    }

    /** Reports one traced request without allowing recoverable monitoring failure to mask host behavior. */
    private fun reportHttpUrlConnectionSafely(
        url: String,
        method: String,
        statusCode: Int,
        durationMs: Long,
        requestSize: Long,
        responseSize: Long,
        error: String?
    ) {
        try {
            onRequestComplete(
                url = url,
                method = method,
                statusCode = statusCode,
                durationMs = durationMs,
                requestSize = requestSize.coerceAtLeast(0L),
                responseSize = responseSize,
                error = error
            )
        } catch (reportError: Exception) {
            // APM collection is subordinate to the host network operation.
            Apm.recordInternalError(ERROR_TAG_HTTP_URL_CONNECTION_REPORT, reportError)
        }
    }

    /** Converts a monotonic nanosecond interval to a non-negative millisecond duration. */
    private fun elapsedMillis(startedAtNanos: Long): Long {
        return (monotonicNanos() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
    }

    /** Returns a normalized human-readable transport failure description. */
    private fun describeError(error: Exception): String {
        return error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "network"
        /** 网络请求事件名。 */
        private const val EVENT_NETWORK_REQUEST = "network_request"
        /** 网络错误事件名。 */
        private const val EVENT_NETWORK_ERROR = "network_error"
        /** 聚合统计事件名。 */
        private const val EVENT_NETWORK_AGGREGATE = "network_aggregate"
        /** 分阶段耗时事件名。 */
        private const val EVENT_NETWORK_PHASE = "network_phase"
        /** 字段：URL。 */
        private const val FIELD_URL = "url"
        /** 字段：HTTP 方法。 */
        private const val FIELD_METHOD = "method"
        /** 字段：状态码。 */
        private const val FIELD_STATUS_CODE = "statusCode"
        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"
        /** 字段：请求大小。 */
        private const val FIELD_REQUEST_SIZE = "requestSize"
        /** 字段：响应大小。 */
        private const val FIELD_RESPONSE_SIZE = "responseSize"
        /** 字段：错误信息。 */
        private const val FIELD_ERROR = "error"
        /** 字段：是否慢请求。 */
        private const val FIELD_IS_SLOW = "isSlow"
        /** 字段：总请求数。 */
        private const val FIELD_TOTAL_REQUESTS = "totalRequests"
        /** 字段：成功数。 */
        private const val FIELD_SUCCESS_COUNT = "successCount"
        /** 字段：失败数。 */
        private const val FIELD_ERROR_COUNT = "errorCount"
        /** 字段：平均耗时。 */
        private const val FIELD_AVG_DURATION_MS = "avgDurationMs"
        /** 字段：最大耗时。 */
        private const val FIELD_MAX_DURATION_MS = "maxDurationMs"
        /** 字段：DNS 耗时。 */
        private const val FIELD_DNS_MS = "dnsMs"
        /** 字段：TCP 连接耗时。 */
        private const val FIELD_TCP_MS = "tcpMs"
        /** 字段：TLS 握手耗时。 */
        private const val FIELD_TLS_MS = "tlsMs"
        /** 字段：请求头耗时。 */
        private const val FIELD_REQUEST_HEADER_MS = "requestHeaderMs"
        /** 字段：响应头耗时。 */
        private const val FIELD_RESPONSE_HEADER_MS = "responseHeaderMs"
        /** 字段：响应体耗时。 */
        private const val FIELD_RESPONSE_BODY_MS = "responseBodyMs"
        /** 成功状态码范围起始。 */
        private const val STATUS_CODE_SUCCESS_START = 200
        /** 成功状态码范围结束。 */
        private const val STATUS_CODE_SUCCESS_END = 299
        /** Sentinel status used when no HTTP response was received. */
        private const val STATUS_CODE_NETWORK_ERROR = -1
        /** Method label used only when a custom connection cannot expose its configured method. */
        private const val HTTP_METHOD_UNKNOWN = "UNKNOWN"
        /** Number of nanoseconds in one millisecond. */
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** Internal-error tag for optional HttpURLConnection metadata reads. */
        private const val ERROR_TAG_HTTP_URL_CONNECTION_METADATA = "network_http_url_connection_metadata"
        /** Internal-error tag for a request report that degraded after the host operation. */
        private const val ERROR_TAG_HTTP_URL_CONNECTION_REPORT = "network_http_url_connection_report"
    }
}
