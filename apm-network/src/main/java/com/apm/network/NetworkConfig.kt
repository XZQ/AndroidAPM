package com.apm.network

/**
 * 网络监控模块配置。
 */
data class NetworkConfig(
    /** 是否开启网络请求监控。 */
    val enableNetworkMonitor: Boolean = true,
    /** URL/Payload 字符串最大截取长度。 */
    val maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD_SIZE,
    /** 慢请求阈值（毫秒），超过此值标记为慢请求。 */
    val slowThresholdMs: Long = DEFAULT_SLOW_THRESHOLD_MS,
    /** 聚合上报窗口大小：累计多少条请求后发送一次聚合统计。 */
    val aggregateWindowSize: Int = DEFAULT_AGGREGATE_WINDOW_SIZE
) {
    companion object {
        /** 默认 payload 最大长度：10KB。 */
        private const val DEFAULT_MAX_PAYLOAD_SIZE = 10 * 1024
        /** 默认慢请求阈值：3 秒。 */
        private const val DEFAULT_SLOW_THRESHOLD_MS = 3000L
        /** 默认聚合窗口大小：100 条。 */
        private const val DEFAULT_AGGREGATE_WINDOW_SIZE = 100
    }
}
