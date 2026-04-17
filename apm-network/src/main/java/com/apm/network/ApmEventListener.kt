package com.apm.network

import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp EventListener 分阶段采集。
 * 采集 DNS、TCP、TLS、请求/响应各阶段的精确耗时。
 * OkHttp 为 compileOnly 依赖，运行时不存在时不会加载此类。
 *
 * 使用方式：
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .eventListenerFactory(ApmEventListener.factory(networkModule))
 *     .build()
 * ```
 */
class ApmEventListener(
    /** 网络模块引用，用于上报数据。 */
    private val networkModule: NetworkModule,
    /** 慢请求阈值（毫秒）。 */
    private val slowThresholdMs: Long
) : EventListener() {

    /** 按 Call 存储的计时数据。 */
    private val callTimings = ConcurrentHashMap<Call, CallTiming>()

    /** 单次请求的计时数据。 */
    data class CallTiming(
        /** DNS 开始时间（纳秒）。 */
        var dnsStartNs: Long = 0L,
        /** DNS 耗时（毫秒）。 */
        var dnsMs: Long = 0L,
        /** TCP 连接开始时间。 */
        var connectStartNs: Long = 0L,
        /** TCP 连接耗时。 */
        var tcpMs: Long = 0L,
        /** TLS 开始时间。 */
        var tlsStartNs: Long = 0L,
        /** TLS 耗时。 */
        var tlsMs: Long = 0L,
        /** 请求头开始时间。 */
        var requestHeaderStartNs: Long = 0L,
        /** 请求头耗时。 */
        var requestHeaderMs: Long = 0L,
        /** 响应头开始时间。 */
        var responseHeaderStartNs: Long = 0L,
        /** 响应头耗时。 */
        var responseHeaderMs: Long = 0L,
        /** 响应体开始时间。 */
        var responseBodyStartNs: Long = 0L,
        /** 响应体耗时。 */
        var responseBodyMs: Long = 0L,
        /** 请求总开始时间。 */
        var callStartNs: Long = 0L,
        /** 请求 URL。 */
        var url: String = "",
        /** HTTP 方法。 */
        var method: String = ""
    )

    override fun callStart(call: Call) {
        val timing = CallTiming()
        timing.callStartNs = System.nanoTime()
        // OkHttp 4.x 中 request() 是函数调用，不是属性
        timing.url = call.request().url.toString()
        timing.method = call.request().method
        callTimings[call] = timing
    }

    override fun dnsStart(call: Call, domainName: String) {
        callTimings[call]?.dnsStartNs = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        callTimings[call]?.let {
            it.dnsMs = elapsedMs(it.dnsStartNs)
        }
    }

    override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
        callTimings[call]?.connectStartNs = System.nanoTime()
    }

    override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        callTimings[call]?.let {
            it.tcpMs = elapsedMs(it.connectStartNs)
        }
    }

    override fun connectFailed(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        callTimings.remove(call)
    }

    override fun secureConnectStart(call: Call) {
        callTimings[call]?.tlsStartNs = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        callTimings[call]?.let {
            it.tlsMs = elapsedMs(it.tlsStartNs)
        }
    }

    override fun requestHeadersStart(call: Call) {
        callTimings[call]?.requestHeaderStartNs = System.nanoTime()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        callTimings[call]?.let {
            it.requestHeaderMs = elapsedMs(it.requestHeaderStartNs)
        }
    }

    override fun responseHeadersStart(call: Call) {
        callTimings[call]?.responseHeaderStartNs = System.nanoTime()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        callTimings[call]?.let {
            it.responseHeaderMs = elapsedMs(it.responseHeaderStartNs)
        }
    }

    override fun responseBodyStart(call: Call) {
        callTimings[call]?.responseBodyStartNs = System.nanoTime()
    }

    /**
     * 响应体读取完成回调。
     * OkHttp 4.11.0 签名为 responseBodyEnd(call, byteCount)，不含 Response 参数。
     */
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        callTimings[call]?.let {
            it.responseBodyMs = elapsedMs(it.responseBodyStartNs)
        }
    }

    override fun callEnd(call: Call) {
        val timing = callTimings.remove(call) ?: return
        val totalMs = elapsedMs(timing.callStartNs)
        reportNetworkStats(timing, totalMs, null)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val timing = callTimings.remove(call) ?: return
        val totalMs = elapsedMs(timing.callStartNs)
        reportNetworkStats(timing, totalMs, ioe.message ?: ioe.javaClass.simpleName)
    }

    /**
     * 上报分阶段网络统计数据。
     * 通过 NetworkModule.onRequestComplete 回调原有接口，
     * 同时额外上报分阶段耗时。
     */
    private fun reportNetworkStats(timing: CallTiming, totalMs: Long, error: String?) {
        // 调用原有接口（兼容）
        networkModule.onRequestComplete(
            url = timing.url,
            method = timing.method,
            statusCode = if (error != null) STATUS_CODE_NETWORK_ERROR else STATUS_CODE_SUCCESS,
            durationMs = totalMs,
            error = error
        )
        // 上报分阶段耗时
        val stats = NetworkRequestStats(
            url = timing.url.take(MAX_URL_LENGTH),
            method = timing.method,
            statusCode = if (error != null) STATUS_CODE_NETWORK_ERROR else STATUS_CODE_SUCCESS,
            dnsMs = timing.dnsMs,
            tcpMs = timing.tcpMs,
            tlsMs = timing.tlsMs,
            requestHeaderMs = timing.requestHeaderMs,
            responseHeaderMs = timing.responseHeaderMs,
            responseBodyMs = timing.responseBodyMs,
            totalMs = totalMs,
            error = error
        )
        networkModule.onNetworkPhaseStats(stats)
    }

    /** 计算经过时间（纳秒 → 毫秒）。 */
    private fun elapsedMs(startNs: Long): Long {
        return if (startNs > 0L) (System.nanoTime() - startNs) / NANOS_PER_MS else 0L
    }

    companion object {
        /** 每毫秒纳秒数。 */
        private const val NANOS_PER_MS = 1_000_000L
        /** URL 最大长度。 */
        private const val MAX_URL_LENGTH = 500
        /** 网络错误状态码。 */
        private const val STATUS_CODE_NETWORK_ERROR = -1
        /** 成功状态码。 */
        private const val STATUS_CODE_SUCCESS = 200

        /**
         * 创建 EventListener.Factory。
         * 用于 OkHttp Builder 的 eventListenerFactory 方法。
         */
        fun factory(networkModule: NetworkModule, slowThresholdMs: Long = 3000L): EventListener.Factory {
            return EventListener.Factory { ApmEventListener(networkModule, slowThresholdMs) }
        }
    }
}
