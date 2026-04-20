package com.apm.uploader

import com.apm.model.ApmEvent

/**
 * 事件上传接口。
 * 负责将事件发送到服务端或本地输出。
 */
interface ApmUploader {
    /**
     * 上传单条事件。
     * @param event 要上传的事件
     * @return true 表示上传成功或已被可靠接管，false 表示本次上传失败
     */
    fun upload(event: ApmEvent): Boolean

    /**
     * 关闭上传器，释放后台线程或网络资源。
     * 默认实现为空，供无状态 uploader 直接复用。
     */
    fun shutdown() = Unit
}
