package com.apm.core

import com.apm.uploader.ApmUploader
import com.apm.uploader.HttpApmUploader
import com.apm.uploader.LogcatApmUploader
import com.apm.uploader.RetryPolicy
import com.apm.uploader.RetryingApmUploader
import com.apm.uploader.UploaderLogger

/**
 * 上传器工厂。
 * 统一封装默认上传器选择和重试包装逻辑。
 */
internal object UploaderFactory {

    /**
     * 根据配置创建最终 uploader。
     *
     * 选择顺序：
     * 1. 显式传入的自定义 uploader
     * 2. `http(s)` endpoint → [HttpApmUploader]
     * 3. 其他情况 → [LogcatApmUploader]
     *
     * @param config APM 全局配置
     * @param durableStore 存储是否支持持久 outbox（决定重试归属）
     * @param logger 核心日志实现，适配为 uploader 内部日志以尊重 debugLogging 开关
     * @return 可直接交给分发器使用的 uploader
     */
    fun create(config: ApmConfig, durableStore: Boolean = false, logger: ApmLogger? = null): ApmUploader {
        // 把 core 的 ApmLogger 适配为 uploader 模块的 UploaderLogger；
        // 未传入时退回 uploader 模块默认实现（警告/错误可见，调试静默）
        val uploaderLogger = logger?.let { adaptLogger(it) } ?: UploaderLogger.DEFAULT
        val baseUploader = config.uploader
            ?: createDefaultUploader(config.endpoint, config, uploaderLogger)
        // The persistent worker owns retries so wrapping it would create two queues.
        if (!config.enableRetry || durableStore) {
            return baseUploader
        }
        return RetryingApmUploader(
            delegate = baseUploader,
            retryPolicy = RetryPolicy(
                maxRetries = config.maxRetries,
                baseDelayMs = config.retryBaseDelayMs
            ),
            logger = uploaderLogger
        )
    }

    /**
     * 将 core 的 [ApmLogger] 适配为 uploader 模块的 [UploaderLogger]。
     * apm-uploader 不依赖 apm-core，因此在 core 侧做单向适配。
     *
     * @param logger 核心日志实现
     * @return 转发到核心日志的 uploader 日志实现
     */
    private fun adaptLogger(logger: ApmLogger): UploaderLogger = object : UploaderLogger {
        /** 调试日志转发到核心 logger（受 debugLogging 门控）。 */
        override fun d(message: String) = logger.d(message)

        /** 警告日志转发到核心 logger。 */
        override fun w(message: String) = logger.w(message)

        /** 错误日志转发到核心 logger。 */
        override fun e(message: String, throwable: Throwable?) = logger.e(message, throwable)
    }

    /**
     * 基于 endpoint 和配置构建默认 uploader。
     * 传入完整配置以支持序列化格式选择。
     *
     * @param endpoint 上传地址
     * @param config APM 全局配置
     * @param uploaderLogger uploader 内部日志实现
     * @return HTTP 或 Logcat uploader
     */
    private fun createDefaultUploader(endpoint: String, config: ApmConfig, uploaderLogger: UploaderLogger): ApmUploader {
        return if (endpoint.startsWith(HTTP_PREFIX) || endpoint.startsWith(HTTPS_PREFIX)) {
            HttpApmUploader(
                endpoint = endpoint,
                enableGzip = config.enableHttpGzip,
                serializationFormat = config.serializationFormat,
                logger = uploaderLogger
            )
        } else {
            LogcatApmUploader(endpoint = endpoint)
        }
    }

    /** HTTP endpoint 前缀。 */
    private const val HTTP_PREFIX = "http://"

    /** HTTPS endpoint 前缀。 */
    private const val HTTPS_PREFIX = "https://"
}
