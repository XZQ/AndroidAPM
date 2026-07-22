package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmResourceContext
import com.apm.model.SerializationFormat
import com.apm.uploader.ApmUploader
import com.apm.uploader.LogcatApmUploader
import org.junit.Assert.*
import org.junit.Test

/**
 * ApmConfig 全局配置测试。
 * 验证默认值和自定义参数覆盖。
 */
class ApmConfigTest {

    /** 默认 endpoint 为空字符串。 */
    @Test
    fun `default endpoint is empty`() {
        val config = ApmConfig()
        assertEquals("", config.endpoint)
    }

    /** 默认关闭调试日志。 */
    @Test
    fun `default debugLogging is false`() {
        val config = ApmConfig()
        assertFalse(config.debugLogging)
    }

    /** 默认开启 PII 脱敏。 */
    @Test
    fun `default PII sanitization is enabled`() {
        assertTrue(ApmConfig().enablePiiSanitization)
    }

    /** 默认开启独立的 SDK 自诊断日志。 */
    @Test
    fun `default diagnostics config is enabled`() {
        assertTrue(ApmConfig().diagnostics.enabled)
    }

    /** 默认进程策略为主进程。 */
    @Test
    fun `default processStrategy is main only`() {
        val config = ApmConfig()
        assertEquals(ProcessStrategy.MAIN_PROCESS_ONLY, config.processStrategy)
    }

    /** 默认限流每窗口 10 条。 */
    @Test
    fun `default rateLimitEventsPerWindow is 10`() {
        val config = ApmConfig()
        assertEquals(10, config.rateLimitEventsPerWindow)
    }

    /** 默认限流窗口 60 秒。 */
    @Test
    fun `default rateLimitWindowMs is 60 seconds`() {
        val config = ApmConfig()
        assertEquals(60_000L, config.rateLimitWindowMs)
    }

    /** 默认最大重试 3 次。 */
    @Test
    fun `default maxRetries is 3`() {
        val config = ApmConfig()
        assertEquals(3, config.maxRetries)
    }

    /** 默认重试基础延迟 1 秒。 */
    @Test
    fun `default retryBaseDelayMs is 1 second`() {
        val config = ApmConfig()
        assertEquals(1000L, config.retryBaseDelayMs)
    }

    /** 默认开启上传重试。 */
    @Test
    fun `default enableRetry is true`() {
        val config = ApmConfig()
        assertTrue(config.enableRetry)
    }

    /** 默认 HTTP 上传开启 Gzip。 */
    @Test
    fun `default enableHttpGzip is true`() {
        val config = ApmConfig()
        assertTrue(config.enableHttpGzip)
    }

    /** 默认 HTTP Headers 和动态 provider 都不注入宿主凭据。 */
    @Test
    fun `default http authentication inputs are empty`() {
        val config = ApmConfig()
        assertTrue(config.httpHeaders.isEmpty())
        assertTrue(config.httpHeaderProvider.currentHeaders().isEmpty())
        assertFalse(config.enableDynamicHttpEndpoint)
    }

    /** 默认上传租约为两分钟。 */
    @Test
    fun `default upload lease is two minutes`() {
        assertEquals(120_000L, ApmConfig().uploadLeaseDurationMs)
    }

    /** 默认 durable payload 同时限制单事件和活跃总量。 */
    @Test
    fun `default durable payload budgets are bounded`() {
        val config = ApmConfig()

        assertEquals(256 * 1024, config.maxEventPayloadBytes)
        assertEquals(64L * 1024L * 1024L, config.maxStoredPayloadBytes)
        assertEquals(1024 * 1024, config.maxUploadBatchBytes)
    }

    /** 默认 dispatcher 在 75% 水位限制单模块占用到队列容量的 50%。 */
    @Test
    fun `default dispatcher module isolation is bounded`() {
        val config = ApmConfig()

        assertTrue(config.enableDispatcherModuleIsolation)
        assertEquals(75, config.dispatcherIsolationHighWatermarkPercent)
        assertEquals(50, config.dispatcherMaxModuleQueueSharePercent)
    }

    /** 默认业务上下文保持兼容的同步快照，并提供一秒异步刷新配置。 */
    @Test
    fun `default business context capture preserves synchronous semantics`() {
        val config = ApmConfig()

        assertEquals(BizContextCaptureMode.SYNCHRONOUS, config.bizContextCaptureMode)
        assertEquals(1_000L, config.bizContextRefreshIntervalMs)
    }

    /** 自定义参数应正确覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            debugLogging = false,
            processStrategy = ProcessStrategy.ALL_PROCESSES,
            rateLimitEventsPerWindow = 50,
            rateLimitWindowMs = 120_000L,
            uploadLeaseDurationMs = 45_000L,
            enableDispatcherModuleIsolation = false,
            dispatcherIsolationHighWatermarkPercent = 80,
            dispatcherMaxModuleQueueSharePercent = 40,
            bizContextCaptureMode = BizContextCaptureMode.ASYNC_CACHED,
            bizContextRefreshIntervalMs = 5_000L
        )
        assertEquals("https://apm.example.com", config.endpoint)
        assertFalse(config.debugLogging)
        assertEquals(ProcessStrategy.ALL_PROCESSES, config.processStrategy)
        assertEquals(50, config.rateLimitEventsPerWindow)
        assertEquals(120_000L, config.rateLimitWindowMs)
        assertEquals(45_000L, config.uploadLeaseDurationMs)
        assertFalse(config.enableDispatcherModuleIsolation)
        assertEquals(80, config.dispatcherIsolationHighWatermarkPercent)
        assertEquals(40, config.dispatcherMaxModuleQueueSharePercent)
        assertEquals(BizContextCaptureMode.ASYNC_CACHED, config.bizContextCaptureMode)
        assertEquals(5_000L, config.bizContextRefreshIntervalMs)
    }

    /** ProcessStrategy 枚举完整性。 */
    @Test
    fun `processStrategy enum has three values`() {
        assertEquals(3, ProcessStrategy.values().size)
    }

    /** Runtime snapshot must not retain mutable host collection ownership. */
    @Test
    fun `runtime snapshot freezes collection configuration`() {
        val context = mutableMapOf("app" to "before")
        val headers = mutableMapOf("X-App" to "before")
        val modules = mutableListOf("network")
        val snapshot = ApmConfig(
            defaultContext = context,
            httpHeaders = headers,
            customProcessModules = mapOf("worker" to modules)
        ).snapshotForRuntime()

        context["app"] = "after"
        headers["X-App"] = "after"
        modules += "io"

        assertEquals("before", snapshot.defaultContext["app"])
        assertEquals("before", snapshot.httpHeaders["X-App"])
        assertEquals(listOf("network"), snapshot.customProcessModules["worker"])
    }

    /** Compatibility mode keeps the existing empty-endpoint behavior for source compatibility. */
    @Test
    fun `compatibility profile accepts existing defaults`() {
        ApmConfig().validateForRuntime()
    }

    /** Strict production requires an explicit grant and a secure delivery path. */
    @Test
    fun `strict production accepts explicit consent and https`() {
        ApmConfig(
            runtimeProfile = ApmRuntimeProfile.PRODUCTION_STRICT,
            initialCollectionConsent = CollectionConsent.GRANTED,
            endpoint = "https://collector.example.com/v1/events",
            serializationFormat = SerializationFormat.PROTOBUF_ENVELOPE_V2,
            resourceContext = productionResource()
        ).validateForRuntime()
    }

    /** A custom non-Logcat uploader is a valid strict delivery path without an endpoint string. */
    @Test
    fun `strict production accepts custom uploader`() {
        ApmConfig(
            runtimeProfile = ApmRuntimeProfile.PRODUCTION_STRICT,
            initialCollectionConsent = CollectionConsent.GRANTED,
            uploader = AcceptingUploader()
        ).validateForRuntime()
    }

    /** Missing consent, unsafe endpoints, and weakened privacy settings fail before initialization. */
    @Test
    fun `strict production rejects unsafe configuration`() {
        val strictBase = ApmConfig(
            runtimeProfile = ApmRuntimeProfile.PRODUCTION_STRICT,
            initialCollectionConsent = CollectionConsent.GRANTED,
            endpoint = "https://collector.example.com/v1/events",
            serializationFormat = SerializationFormat.PROTOBUF_ENVELOPE_V2,
            resourceContext = productionResource()
        )

        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(initialCollectionConsent = CollectionConsent.UNSPECIFIED).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(endpoint = "").validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(endpoint = "http://collector.example.com").validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(endpoint = "logcat://production").validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(enablePiiSanitization = false).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(debugLogging = true).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(storageType = StorageType.FILE).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(uploader = LogcatApmUploader()).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(serializationFormat = SerializationFormat.PROTOBUF).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(resourceContext = ApmResourceContext()).validateForRuntime()
        }
        assertThrows(IllegalArgumentException::class.java) {
            strictBase.copy(maxUploadBatchBytes = strictBase.maxEventPayloadBytes).validateForRuntime()
        }
    }

    /** Complete anonymous standard resource used by strict default HTTP tests. */
    private fun productionResource(): ApmResourceContext = ApmResourceContext(
        serviceName = "wallet",
        serviceVersion = "1.0.0",
        deploymentEnvironment = "production",
        installationId = "install-test"
    )

    /** Minimal custom uploader used only to validate strict-profile routing. */
    private class AcceptingUploader : ApmUploader {
        /** Accepts the test event without performing IO. */
        override fun upload(event: ApmEvent): Boolean = true
    }
}
