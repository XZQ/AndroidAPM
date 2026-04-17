package com.didi.apm.network

/**
 * 单次网络请求的分阶段统计数据。
 * 由 ApmEventListener 采集 OkHttp 各阶段耗时后填充。
 * 包含 DNS、TCP、TLS、请求/响应各阶段的耗时明细。
 */
data class NetworkRequestStats(
    /** 请求 URL。 */
    val url: String = "",
    /** HTTP 方法。 */
    val method: String = "",
    /** HTTP 响应状态码，-1 表示网络错误。 */
    val statusCode: Int = 0,
    /** DNS 解析耗时（毫秒）。 */
    val dnsMs: Long = 0L,
    /** TCP 连接耗时（毫秒）。 */
    val tcpMs: Long = 0L,
    /** TLS 握手耗时（毫秒）。 */
    val tlsMs: Long = 0L,
    /** 请求头发送耗时（毫秒）。 */
    val requestHeaderMs: Long = 0L,
    /** 响应头接收耗时（毫秒）。 */
    val responseHeaderMs: Long = 0L,
    /** 响应体接收耗时（毫秒）。 */
    val responseBodyMs: Long = 0L,
    /** 请求总耗时（毫秒）。 */
    val totalMs: Long = 0L,
    /** 错误信息，非 null 表示请求失败。 */
    val error: String? = null
)
