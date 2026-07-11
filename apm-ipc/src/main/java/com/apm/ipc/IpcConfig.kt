package com.apm.ipc

/**
 * IPC/Binder 监控模块配置。
 */
data class IpcConfig(
    /** 是否开启 IPC 监控。 */
    val enableIpcMonitor: Boolean = true,
    /** Binder 调用耗时告警阈值（毫秒）。 */
    val binderThresholdMs: Long = DEFAULT_BINDER_THRESHOLD_MS,
    /** 主线程 Binder 调用告警阈值（毫秒）。 */
    val mainThreadBinderThresholdMs: Long = DEFAULT_MAIN_THREAD_BINDER_THRESHOLD_MS,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /**
     * Compatibility-only flag for the previously declared generic Binder hook.
     * Android has no supported public API for a process-wide BinderProxy hook;
     * use [IpcModule.traceBinderCall] or [IpcModule.onBinderCallComplete].
     */
    @Deprecated(
        message = "No supported generic Binder hook exists; use traceBinderCall or onBinderCallComplete",
        replaceWith = ReplaceWith("false")
    )
    val enableBinderHook: Boolean = false,
    /** 是否启用 Binder 调用聚合统计。 */
    val enableBinderAggregation: Boolean = true,
    /** 聚合统计窗口大小（调用次数）。 */
    val aggregationWindowSize: Int = DEFAULT_AGGREGATION_WINDOW_SIZE
) {
    companion object {
        /** 默认 Binder 阈值：500ms。 */
        private const val DEFAULT_BINDER_THRESHOLD_MS = 500L
        /** 默认主线程 Binder 阈值：100ms。 */
        private const val DEFAULT_MAIN_THREAD_BINDER_THRESHOLD_MS = 100L
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认聚合窗口大小：50 次调用。 */
        private const val DEFAULT_AGGREGATION_WINDOW_SIZE = 50
    }
}
