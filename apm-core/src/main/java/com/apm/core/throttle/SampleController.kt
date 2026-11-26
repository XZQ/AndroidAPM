package com.apm.core.throttle

import com.apm.core.ProcessSessionId
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
 * Optional lifecycle contract for a [DynamicConfigProvider] backed by a polling control plane.
 *
 * Core starts this provider after publishing a valid APM runtime state and stops it before module
 * teardown. The callback may run on a provider worker thread and must only signal that getters now
 * expose a new verified snapshot.
 */
interface ManagedDynamicConfigProvider : DynamicConfigProvider {

    /**
     * Starts background refresh and reports each effective verified snapshot change.
     *
     * @param onConfigChanged callback invoked after the provider atomically publishes a new view
     */
    fun start(onConfigChanged: () -> Unit)

    /** Stops background refresh and releases provider-owned threads. */
    fun stop()
}

/**
 * 灰度发布控制器。
 * 按功能开关、用户 ID、百分比控制 APM 功能的开启/关闭。
 */
class GrayReleaseController(private val configProvider: DynamicConfigProvider = DynamicConfigProvider.NOOP) {
    /** 本地功能开关覆盖。优先级高于远程配置。 */
    private val featureFlags = ConcurrentHashMap<String, Boolean>()

    /**
     * 检查功能是否开启。优先查本地覆盖，再查远程布尔开关与可选灰度采样比例。
     *
     * @param feature 功能标识，如 "hprof_dump"、"native_monitor"
     * @param userId 可选稳定用户或安装标识；缺失时回退进程会话标识保证单次会话内稳定
     * @return true 表示功能已开启且命中灰度范围
     */
    fun isEnabled(feature: String, userId: String? = null): Boolean {
        return isEnabled(feature, userId, false)
    }

    /**
     * Checks a feature flag with an explicit default for absent remote config.
     *
     * Evaluation order:
     * 1. Local override via [setFeatureOverride] wins unconditionally.
     * 2. Remote boolean `apm.feature.<feature>.enabled` (defaults to [defaultValue]); `false` rejects immediately.
     * 3. Optional remote float `apm.feature.<feature>.sample_rate` (defaults to `1.0f`); `< 1.0f` deterministically
     *    buckets `"$feature:$subject"` where `subject` is non-empty [userId] or [ProcessSessionId.get].
     *
     * @param feature feature identifier
     * @param userId optional stable user or installation identifier
     * @param defaultValue default when no boolean override exists
     * @return resolved feature state
     */
    fun isEnabled(feature: String, userId: String? = null, defaultValue: Boolean): Boolean {
        // 本地覆盖优先（强制全开或强制全关）
        featureFlags[feature]?.let { return it }
        // 远程布尔总开关关闭时直接拒绝，跳过后续分桶计算
        val enabled = configProvider.getBoolean(
            "$FEATURE_KEY_PREFIX$feature$ENABLED_KEY_SUFFIX",
            defaultValue
        )
        if (!enabled) {
            return false
        }
        // 未配置 sample_rate 时默认 1.0f 零分配放行，保持完全向后兼容
        val sampleRate = configProvider.getFloatValue(
            "$FEATURE_KEY_PREFIX$feature$SAMPLE_RATE_KEY_SUFFIX",
            FULL_SAMPLE_RATE
        )
        if (sampleRate >= FULL_SAMPLE_RATE) {
            return true
        }
        // 优先使用稳定 userId，缺失时回退进程级 sessionId；拼接 feature 避免不同功能锁定同一批用户
        val subject = if (!userId.isNullOrEmpty()) userId else ProcessSessionId.get()
        if (sampleRate <= 0.0f) {
            return false
        }
        return bucketForFeatureSubject(feature, subject) < (sampleRate * MODULUS).toInt()
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
     * 使用 FNV-1a + Murmur3 雪崩混合哈希取模，保证同一用户结果稳定且连续编号用户均匀分布。
     *
     * @param userId 用户唯一标识
     * @param sampleRate 采样率 0.0~1.0
     * @return true 表示命中采样
     */
    fun isInSample(userId: String, sampleRate: Float): Boolean {
        if (sampleRate >= FULL_SAMPLE_RATE) {
            return true
        }
        if (sampleRate <= 0.0f) {
            return false
        }
        // 雪崩哈希取模保证同一用户结果稳定，且消除连续数字/同前缀 ID 的多项式哈希聚集偏差
        return bucketFor(userId) < (sampleRate * MODULUS).toInt()
    }

    /**
     * 对单键计算 32 位雪崩哈希并映射到 [0, MODULUS) 桶。
     */
    private fun bucketFor(key: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (i in key.indices) {
            hash = (hash xor key[i].code) * FNV_PRIME
        }
        return finalizeBucket(hash)
    }

    /**
     * 无额外字符串分配地计算 feature + ':' + subject 的雪崩分桶值。
     */
    private fun bucketForFeatureSubject(feature: String, subject: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (i in feature.indices) {
            hash = (hash xor feature[i].code) * FNV_PRIME
        }
        hash = (hash xor COHORT_SEPARATOR_CODE) * FNV_PRIME
        for (i in subject.indices) {
            hash = (hash xor subject[i].code) * FNV_PRIME
        }
        return finalizeBucket(hash)
    }

    /** 应用 MurmurHash3 32-bit finalizer 消除低位相关性并映射到万分位区间。 */
    private fun finalizeBucket(rawHash: Int): Int {
        var mixed = rawHash xor (rawHash ushr SHIFT_16)
        mixed *= MURMUR_MIX_1
        mixed = mixed xor (mixed ushr SHIFT_13)
        mixed *= MURMUR_MIX_2
        mixed = mixed xor (mixed ushr SHIFT_16)
        return (mixed and HASH_MASK) % MODULUS
    }

    companion object {
        /** hashCode 掩码，消除符号位。 */
        private const val HASH_MASK = 0x7FFFFFFF
        /** 取模基数，控制采样精度到万分之一。 */
        private const val MODULUS = 10000
        /** 默认全量放行比例。 */
        private const val FULL_SAMPLE_RATE = 1.0f
        /** 远程灰度配置键前缀。 */
        private const val FEATURE_KEY_PREFIX = "apm.feature."
        /** 远程布尔开关配置键后缀。 */
        private const val ENABLED_KEY_SUFFIX = ".enabled"
        /** 远程灰度采样率配置键后缀。 */
        private const val SAMPLE_RATE_KEY_SUFFIX = ".sample_rate"
        /** feature 与 subject 之间的分隔符编码（':'）。 */
        private const val COHORT_SEPARATOR_CODE = 58
        /** 32-bit FNV-1a 初始偏移基数（0x811c9dc5）。 */
        private const val FNV_OFFSET_BASIS = -0x7ee3623b
        /** 32-bit FNV-1a 质数因子（16777619）。 */
        private const val FNV_PRIME = 0x01000193
        /** MurmurHash3 fmix32 第一轮乘数常量（0x85ebca6b）。 */
        private const val MURMUR_MIX_1 = -0x7a143595
        /** MurmurHash3 fmix32 第二轮乘数常量（0xc2b2ae35）。 */
        private const val MURMUR_MIX_2 = -0x3d4d51cb
        /** 雪崩混合右移位数（16 位）。 */
        private const val SHIFT_16 = 16
        /** 雪崩混合右移位数（13 位）。 */
        private const val SHIFT_13 = 13
    }
}
