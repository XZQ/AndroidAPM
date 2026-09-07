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

    /**
     * 服务端建议的下次重试延迟（毫秒）。
     *
     * 最近一次 upload/uploadBatch 失败时由实现方更新（如 HTTP 429/503 的
     * Retry-After 响应头）；无建议或上次成功时返回 null。
     * 重试调度方（RetryingApmUploader / PersistentUploadWorker）应在
     * 计算退避延迟时尊重该提示。
     *
     * @return 建议延迟毫秒数，null 表示无建议
     */
    fun retryAfterHintMs(): Long? = null
}

/**
 * Upload contract for transports that can send multiple events in one request.
 */
interface BatchApmUploader : ApmUploader {
    /**
     * Uploads one batch atomically from the caller's perspective.
     *
     * @param events events to upload
     * @return true when the complete batch was accepted
     */
    fun uploadBatch(events: List<ApmEvent>): Boolean

    /**
     * Uploads a single event through the batch implementation.
     *
     * @param event event to upload
     * @return true when accepted
     */
    override fun upload(event: ApmEvent): Boolean = uploadBatch(listOf(event))
}

/** Optional, pure per-event preflight. Existing custom uploaders need not implement this contract. */
interface ValidatingApmUploader : ApmUploader {
    /** Returns a permanent protocol rejection, or null when normal transport should be attempted. */
    fun rejectionReason(event: ApmEvent): UploadRejectionReason?
}

/** Payload-free local rejection reasons; these are not collector acknowledgements. */
enum class UploadRejectionReason {
    /** Legacy durable events have no historical occurrence identity for strict V3. */
    OCCURRENCE_REQUIRED,
    /** An existing occurrence violates the strict V3 contract. */
    OCCURRENCE_INVALID,
    /** V2 cannot carry the supplied V3 occurrence. */
    OCCURRENCE_UNSUPPORTED
}
