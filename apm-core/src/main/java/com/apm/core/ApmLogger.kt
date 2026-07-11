package com.apm.core

import android.util.Log
import com.apm.core.diagnostics.DiagnosticLevel
import com.apm.core.diagnostics.DiagnosticRecorder

/**
 * APM 日志接口。所有模块通过此接口输出日志，
 * 便于在测试环境替换为空实现或自定义实现。
 */
interface ApmLogger {
    /** 调试级别日志。 */
    fun d(message: String)
    /** 警告级别日志。 */
    fun w(message: String)
    /** 错误级别日志，附带可选异常堆栈。 */
    fun e(message: String, throwable: Throwable? = null)
}

/**
 * 基于 Android Logcat 的日志实现。
 * 调试日志受 [enabled] 开关控制，警告和错误始终输出。
 */
internal class AndroidApmLogger(
    /** Whether debug-level output is enabled. */
    private val enabled: Boolean,
    /** Independent local diagnostics recorder. */
    private val diagnostics: DiagnosticRecorder? = null
) : ApmLogger {

    override fun d(message: String) {
        // 仅在开启调试模式时输出，避免线上性能开销
        if (enabled) {
            Log.d(TAG, message)
            diagnostics?.record(DiagnosticLevel.DEBUG, CORE_COMPONENT, null, message, null)
        }
    }

    override fun w(message: String) {
        // 警告始终输出，不丢弃
        Log.w(TAG, message)
        diagnostics?.record(DiagnosticLevel.WARN, CORE_COMPONENT, null, message, null)
    }

    override fun e(message: String, throwable: Throwable?) {
        // 错误始终输出，附带异常堆栈便于排查
        Log.e(TAG, message, throwable)
        diagnostics?.record(DiagnosticLevel.ERROR, CORE_COMPONENT, null, message, throwable)
    }

    companion object {
        /** Logcat tag，统一前缀便于过滤。 */
        private const val TAG = "AndroidAPM"
        /** Default component for legacy unscoped logger messages. */
        private const val CORE_COMPONENT = "core"
    }
}
