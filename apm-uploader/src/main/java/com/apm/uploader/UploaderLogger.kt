package com.apm.uploader

import android.util.Log

/**
 * 上传模块内部日志接口。
 *
 * apm-uploader 不依赖 apm-core（依赖方向是 core → uploader），无法直接使用
 * core 的 ApmLogger。通过该接口由宿主（如 apm-core 的 UploaderFactory）注入
 * 适配实现，使全局 debugLogging 开关能够门控上传器日志输出。
 */
interface UploaderLogger {

    /** 输出调试级别日志。 */
    fun d(message: String)

    /** 输出警告级别日志。 */
    fun w(message: String)

    /**
     * 输出错误级别日志。
     *
     * @param message 错误描述
     * @param throwable 可选异常堆栈
     */
    fun e(message: String, throwable: Throwable? = null)

    companion object {
        /** Logcat tag，与历史输出保持一致便于过滤。 */
        private const val TAG = "ApmUploader"

        /**
         * 默认实现：调试日志静默，警告与错误输出到 Logcat。
         * 直接实例化 uploader（未经 UploaderFactory 注入）时的兜底行为。
         */
        val DEFAULT: UploaderLogger = object : UploaderLogger {
            override fun d(message: String) {
                // 默认静默：未注入宿主 logger 时不输出调试日志
            }

            override fun w(message: String) {
                // 警告始终输出，保证线上问题可见
                Log.w(TAG, message)
            }

            override fun e(message: String, throwable: Throwable?) {
                // 错误始终输出，附带异常堆栈便于排查
                Log.e(TAG, message, throwable)
            }
        }
    }
}
