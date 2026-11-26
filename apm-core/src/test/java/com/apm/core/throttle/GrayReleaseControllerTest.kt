package com.apm.core.throttle

import org.junit.Assert.*
import org.junit.Test

/**
 * GrayReleaseController 灰度发布控制器测试。
 * 验证功能开关、本地覆盖、采样率判断逻辑。
 */
class GrayReleaseControllerTest {

    /** 默认（无远程配置）时功能应为关闭。 */
    @Test
    fun `feature disabled by default without config`() {
        val controller = GrayReleaseController()
        assertFalse(controller.isEnabled("test_feature"))
    }

    /** 本地覆盖应能开启功能。 */
    @Test
    fun `feature override enables feature`() {
        val controller = GrayReleaseController()
        controller.setFeatureOverride("test_feature", true)
        assertTrue(controller.isEnabled("test_feature"))
    }

    /** 本地覆盖应能关闭功能。 */
    @Test
    fun `feature override disables feature`() {
        val controller = GrayReleaseController()
        controller.setFeatureOverride("test_feature", true)
        controller.setFeatureOverride("test_feature", false)
        assertFalse(controller.isEnabled("test_feature"))
    }

    /** 不同功能标识互不影响。 */
    @Test
    fun `different features are independent`() {
        val controller = GrayReleaseController()
        controller.setFeatureOverride("feature_a", true)
        assertTrue(controller.isEnabled("feature_a"))
        assertFalse(controller.isEnabled("feature_b"))
    }

    /** 采样率 1.0 应全部命中。 */
    @Test
    fun `sample rate 1_0 always returns true`() {
        val controller = GrayReleaseController()
        assertTrue(controller.isInSample("any_user", 1.0f))
    }

    /** 采样率 0.0 应全部不命中。 */
    @Test
    fun `sample rate 0_0 always returns false`() {
        val controller = GrayReleaseController()
        assertFalse(controller.isInSample("any_user", 0.0f))
    }

    /** 同一用户 + 同一采样率，多次调用结果一致。 */
    @Test
    fun `same user gets consistent sample result`() {
        val controller = GrayReleaseController()
        val results = (1..10).map { controller.isInSample("user123", 0.5f) }
        assertTrue("All results should be the same", results.toSet().size == 1)
    }

    /** 采样率 0.5 时，大批用户应大致各半。 */
    @Test
    fun `sample rate 0_5 splits users roughly in half`() {
        val controller = GrayReleaseController()
        val sampleCount = (1..1000).count { controller.isInSample("user_$it", 0.5f) }
        // 允许 ±10% 误差
        assertTrue("Sample count $sampleCount should be between 400 and 600",
            sampleCount in 400..600)
    }

    /** 远程 sample_rate 应按 feature + userId 稳定分桶，且匿名会话内结果稳定。 */
    @Test
    fun `remote sample rate buckets users deterministically per feature`() {
        val provider = object : DynamicConfigProvider {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = true
            override fun getLongValue(key: String, defaultValue: Long): Long = defaultValue
            override fun getFloatValue(key: String, defaultValue: Float): Float = when (key) {
                "apm.feature.module.fps.sample_rate" -> 0.5f
                "apm.feature.feature_a.sample_rate" -> 0.2f
                "apm.feature.feature_b.sample_rate" -> 0.2f
                else -> defaultValue
            }
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }
        val controller = GrayReleaseController(provider)

        // 50% 灰度在大样本用户上均匀切分，且同一 userId 多次判定一致
        val enabledCount = (1..1000).count { controller.isEnabled("module.fps", userId = "user_$it", defaultValue = true) }
        assertTrue("Enabled count $enabledCount should be between 400 and 600", enabledCount in 400..600)
        val stableUserResult = (1..10).map { controller.isEnabled("module.fps", userId = "stable_user_42", defaultValue = true) }
        assertEquals(1, stableUserResult.toSet().size)

        // 不同功能使用各自盐值，避免 20% 灰度完全锁定同一批用户
        val cohortA = (1..500).filter { controller.isEnabled("feature_a", userId = "u_$it", defaultValue = true) }.toSet()
        val cohortB = (1..500).filter { controller.isEnabled("feature_b", userId = "u_$it", defaultValue = true) }.toSet()
        assertNotEquals("Independent features should not select identical cohorts", cohortA, cohortB)

        // userId 缺失时回退进程级会话标识，保证会话内多次查询不抖动
        val anonymousResults = (1..10).map { controller.isEnabled("module.fps", userId = null, defaultValue = true) }
        assertEquals(1, anonymousResults.toSet().size)
    }

    /** 远程布尔 false 优先于正采样率关闭，而本地 override 优先于零采样率强制开启。 */
    @Test
    fun `disabled remote boolean overrides positive sample rate and local override wins`() {
        val provider = object : DynamicConfigProvider {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = when (key) {
                "apm.feature.disabled_feature.enabled" -> false
                "apm.feature.zero_rate_feature.enabled" -> true
                else -> defaultValue
            }
            override fun getLongValue(key: String, defaultValue: Long): Long = defaultValue
            override fun getFloatValue(key: String, defaultValue: Float): Float = when (key) {
                "apm.feature.disabled_feature.sample_rate" -> 1.0f
                "apm.feature.zero_rate_feature.sample_rate" -> 0.0f
                else -> defaultValue
            }
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }
        val controller = GrayReleaseController(provider)

        assertFalse(controller.isEnabled("disabled_feature", userId = "user_1", defaultValue = true))
        assertFalse(controller.isEnabled("zero_rate_feature", userId = "user_1", defaultValue = true))

        controller.setFeatureOverride("zero_rate_feature", true)
        assertTrue(controller.isEnabled("zero_rate_feature", userId = "user_1", defaultValue = true))
    }
}

/**
 * DynamicConfigProvider.NOOP 测试。
 * 验证空实现始终返回默认值。
 */
class DynamicConfigProviderTest {

    /** NOOP 的 getBoolean 应返回默认值。 */
    @Test
    fun `noop returns default boolean`() {
        assertTrue(DynamicConfigProvider.NOOP.getBoolean("key", true))
        assertFalse(DynamicConfigProvider.NOOP.getBoolean("key", false))
    }

    /** NOOP 的 getLongValue 应返回默认值。 */
    @Test
    fun `noop returns default long`() {
        assertEquals(42L, DynamicConfigProvider.NOOP.getLongValue("key", 42L))
    }

    /** NOOP 的 getFloatValue 应返回默认值。 */
    @Test
    fun `noop returns default float`() {
        assertEquals(3.14f, DynamicConfigProvider.NOOP.getFloatValue("key", 3.14f), 0.001f)
    }

    /** NOOP 的 getString 应返回默认值。 */
    @Test
    fun `noop returns default string`() {
        assertEquals("default", DynamicConfigProvider.NOOP.getString("key", "default"))
    }
}
