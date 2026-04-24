package com.apm.core

import com.apm.uploader.ApmUploader
import com.apm.uploader.HttpApmUploader
import com.apm.uploader.LogcatApmUploader
import com.apm.uploader.RetryPolicy
import com.apm.uploader.RetryingApmUploader

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
     * @return 可直接交给分发器使用的 uploader
     */
    fun create(config: ApmConfig): ApmUploader {
        val baseUploader = config.uploader ?: createDefaultUploader(config.endpoint, config)
        if (!config.enableRetry) {
            return baseUploader
        }
        return RetryingApmUploader(
            delegate = baseUploader,
            retryPolicy = RetryPolicy(
                maxRetries = config.maxRetries,
                baseDelayMs = config.retryBaseDelayMs
            )
        )
    }

    /**
     * 基于 endpoint 构建默认 uploader。
     *
     * @param endpoint 上传地址
     * @return HTTP 或 Logcat uploader
     */
    private fun createDefaultUploader(endpoint: String): ApmUploader {
        return if (endpoint.startsWith(HTTP_PREFIX) || endpoint.startsWith(HTTPS_PREFIX)) {
            HttpApmUploader(endpoint = endpoint)
        } else {
            LogcatApmUploader(endpoint = endpoint)
        }
    }

    /**
     * 基于 endpoint 和配置构建默认 uploader。
     * 传入完整配置以支持序列化格式选择。
     *
     * @param endpoint 上传地址
     * @param config APM 全局配置
     * @return HTTP 或 Logcat uploader
     */
    private fun createDefaultUploader(endpoint: String, config: ApmConfig): ApmUploader {
        return if (endpoint.startsWith(HTTP_PREFIX) || endpoint.startsWith(HTTPS_PREFIX)) {
            HttpApmUploader(
                endpoint = endpoint,
                serializationFormat = config.serializationFormat
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
