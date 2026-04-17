package com.apm.network

/**
 * 网络请求聚合统计数据。
 * 在滑动窗口内累计请求的总数、成功/失败数、平均耗时等。
 */
data class NetworkStats(
    /** 总请求数。 */
    val totalRequests: Long = 0,
    /** 成功请求数（2xx 状态码且无错误）。 */
    val successCount: Long = 0,
    /** 失败请求数。 */
    val errorCount: Long = 0,
    /** 平均耗时（毫秒）。 */
    val avgDurationMs: Long = 0,
    /** 最大耗时（毫秒）。 */
    val maxDurationMs: Long = 0
)
