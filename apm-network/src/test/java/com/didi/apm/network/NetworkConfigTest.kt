package com.didi.apm.network

import org.junit.Assert.*
import org.junit.Test

/**
 * NetworkConfig 默认值测试。
 * 验证网络监控配置项默认值正确。
 */
class NetworkConfigTest {

    /** 默认开启网络监控。 */
    @Test
    fun `default enableNetworkMonitor is true`() {
        val config = NetworkConfig()
        assertTrue(config.enableNetworkMonitor)
    }

    /** 默认 payload 最大长度为 10KB。 */
    @Test
    fun `default maxPayloadSize is 10KB`() {
        val config = NetworkConfig()
        assertEquals(10 * 1024, config.maxPayloadSize)
    }

    /** 默认慢请求阈值为 3 秒。 */
    @Test
    fun `default slowThresholdMs is 3 seconds`() {
        val config = NetworkConfig()
        assertEquals(3000L, config.slowThresholdMs)
    }

    /** 默认聚合窗口大小为 100。 */
    @Test
    fun `default aggregateWindowSize is 100`() {
        val config = NetworkConfig()
        assertEquals(100, config.aggregateWindowSize)
    }

    /** 自定义参数应正确覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = NetworkConfig(
            enableNetworkMonitor = false,
            maxPayloadSize = 2048,
            slowThresholdMs = 5000L,
            aggregateWindowSize = 50
        )
        assertFalse(config.enableNetworkMonitor)
        assertEquals(2048, config.maxPayloadSize)
        assertEquals(5000L, config.slowThresholdMs)
        assertEquals(50, config.aggregateWindowSize)
    }
}

/**
 * NetworkStats 数据类测试。
 * 验证默认值和数据构造。
 */
class NetworkStatsTest {

    /** 默认值应全为 0。 */
    @Test
    fun `default stats are all zero`() {
        val stats = NetworkStats()
        assertEquals(0L, stats.totalRequests)
        assertEquals(0L, stats.successCount)
        assertEquals(0L, stats.errorCount)
        assertEquals(0L, stats.avgDurationMs)
        assertEquals(0L, stats.maxDurationMs)
    }

    /** 自定义值应正确设置。 */
    @Test
    fun `custom values are set correctly`() {
        val stats = NetworkStats(
            totalRequests = 100,
            successCount = 90,
            errorCount = 10,
            avgDurationMs = 250,
            maxDurationMs = 1500
        )
        assertEquals(100L, stats.totalRequests)
        assertEquals(90L, stats.successCount)
        assertEquals(10L, stats.errorCount)
        assertEquals(250L, stats.avgDurationMs)
        assertEquals(1500L, stats.maxDurationMs)
    }

    /** 成功 + 失败 = 总数。 */
    @Test
    fun `success plus error equals total`() {
        val stats = NetworkStats(totalRequests = 200, successCount = 180, errorCount = 20)
        assertEquals(stats.totalRequests, stats.successCount + stats.errorCount)
    }
}
