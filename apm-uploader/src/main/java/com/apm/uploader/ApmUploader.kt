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
     */
    fun upload(event: ApmEvent)
}
