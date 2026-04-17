package com.apm.threadmonitor

import org.junit.Assert.*
import org.junit.Test

/**
 * ThreadMonitorModule 配置和参数测试。
 * 注：Module 构造函数依赖 Handler(Looper.getMainLooper())，
 * 纯 JUnit 环境无主线程 Looper，因此仅测试 Config 层。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 */
class ThreadMonitorModuleTest {

    /** 默认配置开启监控。 */
    @Test
    fun `default config enables thread monitor`() {
        val config = ThreadMonitorConfig()
        assertTrue(config.enableThreadMonitor)
    }

    /** 默认线程数阈值 100。 */
    @Test
    fun `default thread count threshold is 100`() {
        val config = ThreadMonitorConfig()
        assertEquals(100, config.threadCountThreshold)
    }

    /** 默认同名线程阈值 5。 */
    @Test
    fun `default duplicate thread threshold is 5`() {
        val config = ThreadMonitorConfig()
        assertEquals(5, config.duplicateThreadThreshold)
    }

    /** 默认检测间隔 30 秒。 */
    @Test
    fun `default check interval is 30 seconds`() {
        val config = ThreadMonitorConfig()
        assertEquals(30_000L, config.checkIntervalMs)
    }

    /** 默认堆栈长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = ThreadMonitorConfig()
        assertEquals(4000, config.maxStackTraceLength)
    }

    /** 默认开启线程池监控。 */
    @Test
    fun `default enable thread pool monitor is true`() {
        val config = ThreadMonitorConfig()
        assertTrue(config.enableThreadPoolMonitor)
    }

    /** 默认开启线程泄漏检测。 */
    @Test
    fun `default enable thread leak detect is true`() {
        val config = ThreadMonitorConfig()
        assertTrue(config.enableThreadLeakDetect)
    }

    /** 默认队列积压阈值 100。 */
    @Test
    fun `default queue backlog threshold is 100`() {
        val config = ThreadMonitorConfig()
        assertEquals(100, config.queueBacklogThreshold)
    }

    /** 默认线程泄漏阈值 5 分钟。 */
    @Test
    fun `default thread leak threshold is 5 minutes`() {
        val config = ThreadMonitorConfig()
        assertEquals(300_000L, config.threadLeakThresholdMs)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = ThreadMonitorConfig(
            enableThreadMonitor = false,
            threadCountThreshold = 200,
            duplicateThreadThreshold = 10,
            checkIntervalMs = 60_000L
        )
        assertFalse(config.enableThreadMonitor)
        assertEquals(200, config.threadCountThreshold)
        assertEquals(10, config.duplicateThreadThreshold)
        assertEquals(60_000L, config.checkIntervalMs)
    }

    /** 同名线程阈值小于线程数阈值。 */
    @Test
    fun `duplicate threshold is less than thread count threshold`() {
        val config = ThreadMonitorConfig()
        assertTrue(config.duplicateThreadThreshold < config.threadCountThreshold)
    }
}
