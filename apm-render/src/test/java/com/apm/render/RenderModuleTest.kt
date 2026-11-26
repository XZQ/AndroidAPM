package com.apm.render

import org.junit.Assert.*
import org.junit.Test

/**
 * RenderModule 配置和参数测试。
 * 注：Module 构造函数依赖 Handler(Looper.getMainLooper())，
 * 且实现 ActivityLifecycleCallbacks 需要 Activity/View 等 Android 框架类，
 * 纯 JUnit 环境无主线程 Looper，因此仅测试 Config 层。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 */
class RenderModuleTest {

    /** 默认开启渲染监控。 */
    @Test
    fun `default enableRenderMonitor is true`() {
        val config = RenderConfig()
        assertTrue(config.enableRenderMonitor)
    }

    /** 默认 View 层级深度阈值 10。 */
    @Test
    fun `default viewDepthThreshold is 10`() {
        val config = RenderConfig()
        assertEquals(EXPECTED_DEPTH_THRESHOLD, config.viewDepthThreshold)
    }

    /** 默认 View 数量阈值 300。 */
    @Test
    fun `default viewCountThreshold is 300`() {
        val config = RenderConfig()
        assertEquals(EXPECTED_COUNT_THRESHOLD, config.viewCountThreshold)
    }

    /** 默认慢帧阈值为 32ms。 */
    @Test
    fun `default slow frame threshold is 32ms`() {
        assertEquals(32L, RenderConfig().slowFrameThresholdMs)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides all defaults`() {
        val config = RenderConfig(
            enableRenderMonitor = false,
            viewDepthThreshold = CUSTOM_DEPTH_THRESHOLD,
            viewCountThreshold = CUSTOM_COUNT_THRESHOLD,
            slowFrameThresholdMs = 48L
        )
        // 验证所有自定义值已正确覆盖
        assertFalse(config.enableRenderMonitor)
        assertEquals(CUSTOM_DEPTH_THRESHOLD, config.viewDepthThreshold)
        assertEquals(CUSTOM_COUNT_THRESHOLD, config.viewCountThreshold)
        assertEquals(48L, config.slowFrameThresholdMs)
    }

    /** data class copy 仅修改指定字段。 */
    @Test
    fun `copy modifies specified fields only`() {
        val original = RenderConfig()
        val modified = original.copy(viewDepthThreshold = CUSTOM_DEPTH_THRESHOLD)
        // 修改的字段
        assertEquals(CUSTOM_DEPTH_THRESHOLD, modified.viewDepthThreshold)
        // 未修改的字段保持默认
        assertTrue(modified.enableRenderMonitor)
        assertEquals(EXPECTED_COUNT_THRESHOLD, modified.viewCountThreshold)
    }

    /** View 数量阈值应大于深度阈值（页面 View 数通常远大于层级深度）。 */
    @Test
    fun `view count threshold is greater than depth threshold`() {
        val config = RenderConfig()
        assertTrue(config.viewCountThreshold > config.viewDepthThreshold)
    }

    /** RenderStats 默认值正确。 */
    @Test
    fun `renderStats default values`() {
        val stats = RenderStats()
        assertEquals(DEFAULT_VIEW_COUNT, stats.viewCount)
        assertEquals(DEFAULT_DEPTH, stats.maxDepth)
        assertEquals(EMPTY_ACTIVITY_NAME, stats.activityName)
    }

    /** RenderStats 自定义值正确。 */
    @Test
    fun `renderStats custom values`() {
        val stats = RenderStats(
            viewCount = CUSTOM_VIEW_COUNT,
            maxDepth = CUSTOM_MAX_DEPTH,
            activityName = TEST_ACTIVITY_NAME
        )
        assertEquals(CUSTOM_VIEW_COUNT, stats.viewCount)
        assertEquals(CUSTOM_MAX_DEPTH, stats.maxDepth)
        assertEquals(TEST_ACTIVITY_NAME, stats.activityName)
    }

    /** RenderStats copy 正确。 */
    @Test
    fun `renderStats copy works`() {
        val original = RenderStats(viewCount = CUSTOM_VIEW_COUNT)
        val copied = original.copy(maxDepth = CUSTOM_MAX_DEPTH)
        assertEquals(CUSTOM_VIEW_COUNT, copied.viewCount)
        assertEquals(CUSTOM_MAX_DEPTH, copied.maxDepth)
    }

    companion object {
        /** 期望的默认深度阈值：10。 */
        private const val EXPECTED_DEPTH_THRESHOLD = 10
        /** 期望的默认数量阈值：300。 */
        private const val EXPECTED_COUNT_THRESHOLD = 300
        /** 自定义深度阈值：20。 */
        private const val CUSTOM_DEPTH_THRESHOLD = 20
        /** 自定义数量阈值：500。 */
        private const val CUSTOM_COUNT_THRESHOLD = 500
        /** 默认 View 数量：0。 */
        private const val DEFAULT_VIEW_COUNT = 0
        /** 默认深度：0。 */
        private const val DEFAULT_DEPTH = 0
        /** 空页面名。 */
        private const val EMPTY_ACTIVITY_NAME = ""
        /** 自定义 View 数量。 */
        private const val CUSTOM_VIEW_COUNT = 250
        /** 自定义最大深度。 */
        private const val CUSTOM_MAX_DEPTH = 12
        /** 测试用 Activity 名。 */
        private const val TEST_ACTIVITY_NAME = "MainActivity"
    }
}
