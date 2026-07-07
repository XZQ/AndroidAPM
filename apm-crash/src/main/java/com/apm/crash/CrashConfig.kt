package com.apm.crash

/**
 * Crash 模块配置。
 */
data class CrashConfig(
    /** 是否开启 Java 层崩溃捕获。 */
    val enableJavaCrash: Boolean = true,
    /** 是否开启 Native 崩溃监控（需要 JNI 层配合）。 */
    val enableNativeCrash: Boolean = false,
    /** 是否允许在 native 信号处理器内直接回调 Java；仅建议调试环境临时开启。 */
    val enableUnsafeNativeSignalCallback: Boolean = false,
    /** 堆栈字符串最大长度，超出截断。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /**
     * 是否在启动时采集 ApplicationExitInfo 退出原因（API 30+）。
     * 覆盖 ANR、native crash、OOM 被杀等 SDK 无法在现场感知的退出。
     */
    val collectExitInfo: Boolean = true,
    /** ANR 退出记录附带系统 trace 的最大字节数。 */
    val maxExitTraceBytes: Int = ExitReasonCollector.DEFAULT_MAX_TRACE_BYTES
) {
    companion object {
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
    }
}
