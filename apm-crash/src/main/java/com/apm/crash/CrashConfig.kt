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
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH
) {
    companion object {
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
    }
}
