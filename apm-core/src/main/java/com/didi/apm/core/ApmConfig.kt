package com.didi.apm.core

import com.didi.apm.core.throttle.DynamicConfigProvider
import com.didi.apm.core.throttle.GrayReleaseController
import com.didi.apm.core.throttle.RateLimiter

/** 进程策略：控制 APM 在哪些进程中初始化。 */
enum class ProcessStrategy {
    /** 仅在主进程初始化，子进程跳过。 */
    MAIN_PROCESS_ONLY,
    /** 所有进程都初始化。 */
    ALL_PROCESSES
}

/**
 * 业务上下文提供者。每次事件上报时调用，将业务动态信息注入事件。
 * 例如：当前用户 ID、设备 ID、AB 实验分组等。
 */
fun interface BizContextProvider {
    /** 返回当前业务上下文键值对，会被合并到每条事件的 globalContext 中。 */
    fun currentContext(): Map<String, String>

    companion object {
        /** 空实现，不注入任何业务上下文。 */
        val EMPTY = BizContextProvider { emptyMap() }
    }
}

/**
 * APM 框架全局配置。
 * 在 [Apm.init] 时传入，初始化后不可修改。
 */
data class ApmConfig(
    /** 上传目标地址。为空时使用 Logcat 本地输出。 */
    val endpoint: String = "",
    /** 是否开启调试日志（Log.d 级别）。 */
    val debugLogging: Boolean = true,
    /** 进程策略：控制 APM 在哪些进程中初始化。 */
    val processStrategy: ProcessStrategy = ProcessStrategy.MAIN_PROCESS_ONLY,
    /** 默认上下文，初始化时传入的静态键值对，每条事件都会携带。 */
    val defaultContext: Map<String, String> = emptyMap(),
    /** 业务上下文提供者，每次 emit 时动态获取。 */
    val bizContextProvider: BizContextProvider = BizContextProvider.EMPTY,

    // --- Phase 5: 限流 ---
    /** 每个时间窗口内允许通过的最大事件数（按 module/name 分桶）。 */
    val rateLimitEventsPerWindow: Int = DEFAULT_RATE_LIMIT_EVENTS,
    /** 限流窗口时长（毫秒）。 */
    val rateLimitWindowMs: Long = DEFAULT_RATE_LIMIT_WINDOW_MS,

    // --- Phase 5: 灰度 ---
    /** 动态配置提供者，对接远程配置中心（Apollo / Firebase 等）。 */
    val dynamicConfigProvider: DynamicConfigProvider = DynamicConfigProvider.NOOP,
    /** 灰度发布控制器，按版本/用户/百分比控制功能开关。 */
    val grayController: GrayReleaseController? = null,

    // --- Phase 5: 重试 ---
    /** 是否开启上传重试（指数退避）。 */
    val enableRetry: Boolean = true,
    /** 最大重试次数。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 重试基础延迟（毫秒），实际延迟 = baseDelay * (multiplier ^ attempt)。 */
    val retryBaseDelayMs: Long = DEFAULT_RETRY_BASE_DELAY_MS
) {
    companion object {
        /** 默认限流：每窗口 10 条事件。 */
        private const val DEFAULT_RATE_LIMIT_EVENTS = 10
        /** 默认限流窗口：60 秒。 */
        private const val DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000L
        /** 默认最大重试次数。 */
        private const val DEFAULT_MAX_RETRIES = 3
        /** 默认重试基础延迟：1 秒。 */
        private const val DEFAULT_RETRY_BASE_DELAY_MS = 1000L
    }
}
