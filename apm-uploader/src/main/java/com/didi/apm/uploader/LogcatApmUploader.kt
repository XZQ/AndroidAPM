package com.didi.apm.uploader

import android.util.Log
import com.didi.apm.model.ApmEvent
import com.didi.apm.model.toLineProtocol

/**
 * Logcat 上传实现。将事件输出到 Android Logcat。
 * 用于开发调试阶段，无需服务端对接。
 */
class LogcatApmUploader(
    /** 上传目标地址。为空时使用占位标识。 */
    private val endpoint: String = ""
) : ApmUploader {

    /**
     * 将事件格式化后输出到 Logcat。
     * 格式：target=xxx line_protocol_content
     */
    override fun upload(event: ApmEvent) {
        val target = endpoint.ifBlank { FALLBACK_TARGET }
        Log.i(TAG, "target=$target ${event.toLineProtocol()}")
    }

    companion object {
        /** Logcat tag。 */
        private const val TAG = "ApmUploader"

        /** endpoint 为空时的占位标识。 */
        private const val FALLBACK_TARGET = "logcat://local"
    }
}
