package com.apm.render

import org.junit.Assert.*
import org.junit.Test

/**
 * RenderConfig 默认值测试。
 */
class RenderConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableRenderMonitor is true`() {
        val config = RenderConfig()
        assertTrue(config.enableRenderMonitor)
    }

    /** 默认 View 绘制阈值 16ms。 */
    @Test
    fun `default viewDrawThresholdMs is 16`() {
        val config = RenderConfig()
        assertEquals(16L, config.viewDrawThresholdMs)
    }

    /** 默认 View 层级深度阈值 10。 */
    @Test
    fun `default viewDepthThreshold is 10`() {
        val config = RenderConfig()
        assertEquals(10, config.viewDepthThreshold)
    }

    /** 默认 View 数量阈值 300。 */
    @Test
    fun `default viewCountThreshold is 300`() {
        val config = RenderConfig()
        assertEquals(300, config.viewCountThreshold)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = RenderConfig(viewDepthThreshold = 15, viewCountThreshold = 500)
        assertEquals(15, config.viewDepthThreshold)
        assertEquals(500, config.viewCountThreshold)
    }
}

/**
 * RenderStats 数据类测试。
 */
class RenderStatsTest {

    /** 默认值。 */
    @Test
    fun `default stats`() {
        val stats = RenderStats()
        assertEquals(0, stats.viewCount)
        assertEquals(0, stats.maxDepth)
    }

    /** 自定义值。 */
    @Test
    fun `custom values`() {
        val stats = RenderStats(viewCount = 250, maxDepth = 12, activityName = "MainActivity")
        assertEquals(250, stats.viewCount)
        assertEquals(12, stats.maxDepth)
        assertEquals("MainActivity", stats.activityName)
    }
}
