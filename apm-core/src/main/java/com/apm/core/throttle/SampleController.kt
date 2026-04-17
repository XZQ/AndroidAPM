package com.apm.core.throttle

import java.util.concurrent.ConcurrentHashMap

/**
 * 动态配置提供者接口。
 * 对接远程配置中心（Apollo、Firebase Remote Config 等），
 * 支持运行时动态调整 APM 行为参数。
 */
interface DynamicConfigProvider {
    /** 获取布尔配置。 */
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    /** 获取长整型配置。 */
    fun getLongValue(key: String, defaultValue: Long): Long
    /** 获取浮点配置。 */
    fun getFloatValue(key: String, defaultValue: Float): Float
    /** 获取字符串配置。 */
    fun getString(key: String, defaultValue: String): String

    companion object {
        /** 空实现，始终返回默认值。用于无远程配置中心的场景。 */
        val NOOP = object : DynamicConfigProvider {
            override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
            override fun getLongValue(key: String, defaultValue: Long) = defaultValue
            override fun getFloatValue(key: String, defaultValue: Float) = defaultValue
            override fun getString(key: String, defaultValue: String) = defaultValue
        }
    }
}

/**
 * 灰度发布控制器。
 * 按功能开关、用户 ID、百分比控制 APM 功能的开启/关闭。
 */
class GrayReleaseController(
    /** 动态配置提供者，用于读取远程开关。 */
    private val configProvider: DynamicConfigProvider = DynamicConfigProvider.NOOP
) {
    /** 本地功能开关覆盖。优先级高于远程配置。 */
    private val featureFlags = ConcurrentHashMap<String, Boolean>()

    /**
     * 检查功能是否开启。优先查本地覆盖，再查远程配置。
     *
     * @param feature 功能标识，如 "hprof_dump"、"native_monitor"
     * @param userId 用户 ID（预留，可用于按用户灰度）
     * @return true 表示功能已开启
     */
    fun isEnabled(feature: String, userId: String? = null): Boolean {
        // 本地覆盖优先
        featureFlags[feature]?.let { return it }
        return configProvider.getBoolean("apm.feature.$feature.enabled", false)
    }

    /**
     * 本地覆盖功能开关。用于调试或强制开启/关闭。
     *
     * @param feature 功能标识
     * @param enabled true 开启，false 关闭
     */
    fun setFeatureOverride(feature: String, enabled: Boolean) {
        featureFlags[feature] = enabled
    }

    /**
     * 判断用户是否在采样范围内。
     * 使用 userId hashCode 取模，保证同一用户结果稳定。
     *
     * @param userId 用户唯一标识
     * @param sampleRate 采样率 0.0~1.0
     * @return true 表示命中采样
     */
    fun isInSample(userId: String, sampleRate: Float): Boolean {
        if (sampleRate >= 1.0f) return true
        if (sampleRate <= 0.0f) return false
        // 取模保证同一用户结果稳定，避免重复开关
        val hash = (userId.hashCode() and HASH_MASK) % MODULUS
        return hash < (sampleRate * MODULUS).toInt()
    }

    companion object {
        /** hashCode 掩码，消除符号位。 */
        private const val HASH_MASK = 0x7FFFFFFF
        /** 取模基数，控制采样精度到万分之一。 */
        private const val MODULUS = 10000
    }
}
