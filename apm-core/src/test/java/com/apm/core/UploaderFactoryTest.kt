package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.core.throttle.DynamicConfigProvider
import com.apm.uploader.HttpEndpointProvider
import com.apm.uploader.HttpApmUploader
import com.apm.uploader.LogcatApmUploader
import com.apm.uploader.RetryingApmUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UploaderFactory 上传器选择测试。
 * 验证 endpoint、自定义 uploader 和重试包装的决策逻辑。
 */
class UploaderFactoryTest {

    /** 自定义 uploader 应优先于 endpoint 规则（非持久路径经异步包装，委托仍是自定义实例）。 */
    @Test
    fun `custom uploader takes precedence over endpoint`() {
        val customUploader = RecordingUploader()
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            uploader = customUploader,
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        assertSame(customUploader, readDelegate(uploader))
    }

    /** HTTP endpoint 应默认创建 HttpApmUploader。 */
    @Test
    fun `http endpoint uses http uploader`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        assertTrue(readDelegate(uploader) is HttpApmUploader)
    }

    /** 默认 HTTP uploader 应跟随 ApmConfig 开启 Gzip。 */
    @Test
    fun `http endpoint enables gzip by default`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        val delegate = readDelegate(uploader)
        assertTrue(delegate is HttpApmUploader)
        assertTrue(readGzipFlag(delegate as HttpApmUploader))
    }

    /** 默认 HTTP uploader 应允许显式关闭 Gzip。 */
    @Test
    fun `http endpoint can disable gzip`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            enableRetry = false,
            enableHttpGzip = false
        )

        val uploader = UploaderFactory.create(config)

        val delegate = readDelegate(uploader)
        assertTrue(delegate is HttpApmUploader)
        assertFalse(readGzipFlag(delegate as HttpApmUploader))
    }

    /** Opt-in endpoint rotation reads the signed dynamic-config key through the HTTP provider. */
    @Test
    fun `dynamic endpoint opt in wires config provider`() {
        val config = ApmConfig(
            endpoint = "https://bootstrap.example/v1/events",
            enableRetry = false,
            enableDynamicHttpEndpoint = true,
            dynamicConfigProvider = FixedDynamicConfigProvider(
                "https://collector.example/v1/events"
            )
        )

        val uploader = readDelegate(UploaderFactory.create(config)) as HttpApmUploader
        val field = HttpApmUploader::class.java.getDeclaredField("endpointProvider")
        field.isAccessible = true
        val provider = field.get(uploader) as HttpEndpointProvider

        assertTrue(
            provider.currentEndpoint(config.endpoint) == "https://collector.example/v1/events"
        )
    }

    /** 空 endpoint 应安全丢弃，且绝不能隐式输出完整事件到 Logcat。 */
    @Test
    fun `blank endpoint uses payload safe discarding uploader`() {
        val logger = RecordingLogger()
        val config = ApmConfig(
            endpoint = "",
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config, logger = logger)

        assertFalse(readDelegate(uploader) is LogcatApmUploader)
        assertTrue(uploader.upload(ApmEvent(module = "test", name = "secret")))
        assertTrue(uploader.upload(ApmEvent(module = "test", name = "second")))
        // 异步包装器在自身 worker 上交付；关闭后丢弃告警必然已发出且仅一次。
        uploader.shutdown()
        assertEquals(1, logger.warnings.size)
        assertFalse(logger.warnings.single().contains("secret"))
    }

    /** Logcat delivery remains available only through an explicit development endpoint. */
    @Test
    fun `explicit logcat endpoint uses logcat uploader`() {
        val uploader = UploaderFactory.create(
            ApmConfig(endpoint = "logcat://development", enableRetry = false)
        )

        assertTrue(readDelegate(uploader) is LogcatApmUploader)
    }

    /** 开启重试时应包装为 RetryingApmUploader。 */
    @Test
    fun `retry enabled wraps base uploader`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            enableRetry = true
        )

        val uploader = UploaderFactory.create(config)

        assertTrue(uploader is RetryingApmUploader)
    }

    /** 非持久路径即使关闭重试也必须经异步包装：交付屏障绝不等待阻塞式传输。 */
    @Test
    fun `non durable delivery is always async even with retry disabled`() {
        val uploader = UploaderFactory.create(
            ApmConfig(endpoint = "https://apm.example.com", enableRetry = false)
        )

        assertTrue(uploader is RetryingApmUploader)
    }

    /** 持久 outbox 与异步接受型上传器的组合会 ACK 未交付行，必须快速失败。 */
    @Test
    fun `durable store rejects async acceptance uploader`() {
        val asyncUploader = RetryingApmUploader(delegate = RecordingUploader())
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            uploader = asyncUploader
        )

        try {
            UploaderFactory.create(config, durableStore = true)
            throw AssertionError("Expected IllegalArgumentException for the lossy composition")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("durable"))
        } finally {
            asyncUploader.shutdown()
        }
    }

    /** 持久 outbox 原样返回同步传输层，重试由持久 worker 单一权威负责。 */
    @Test
    fun `durable store returns synchronous transport unwrapped`() {
        val customUploader = RecordingUploader()
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            uploader = customUploader
        )

        val uploader = UploaderFactory.create(config, durableStore = true)

        assertSame(customUploader, uploader)
    }

    /**
     * 记录型 uploader。
     * 用于验证自定义实例是否被原样返回。
     */
    private class RecordingUploader : com.apm.uploader.ApmUploader {

        /** 收到的事件列表。 */
        val events = mutableListOf<ApmEvent>()

        /**
         * 记录事件并返回成功。
         *
         * @param event 待上传事件
         * @return 始终返回 true
         */
        override fun upload(event: ApmEvent): Boolean {
            // 记录自定义 uploader 是否被实际调用。
            events += event
            return true
        }
    }

    /** Logger that records generic configuration warnings without using Android Logcat. */
    private class RecordingLogger : ApmLogger {
        /** Warning messages emitted by the factory fallback. */
        val warnings = mutableListOf<String>()

        /** Debug output is irrelevant to this test. */
        override fun d(message: String) = Unit

        /** Records one warning for assertions. */
        override fun w(message: String) {
            warnings += message
        }

        /** Error output is irrelevant to this test. */
        override fun e(message: String, throwable: Throwable?) = Unit
    }

    /** Dynamic provider returning one fixed string and defaults for unrelated value types. */
    private class FixedDynamicConfigProvider(
        /** Endpoint returned for the uploader key. */
        private val endpoint: String
    ) : DynamicConfigProvider {
        /** Returns the supplied default. */
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue

        /** Returns the supplied default. */
        override fun getLongValue(key: String, defaultValue: Long): Long = defaultValue

        /** Returns the supplied default. */
        override fun getFloatValue(key: String, defaultValue: Float): Float = defaultValue

        /** Returns the fixed endpoint. */
        override fun getString(key: String, defaultValue: String): String = endpoint
    }

    /**
     * Reads the wrapped delegate when the factory returned an async wrapper, else the uploader itself.
     *
     * @param uploader uploader created by the factory
     * @return the underlying transport selected by endpoint/custom rules
     */
    private fun readDelegate(uploader: com.apm.uploader.ApmUploader): com.apm.uploader.ApmUploader {
        if (uploader !is RetryingApmUploader) {
            return uploader
        }
        val field = RetryingApmUploader::class.java.getDeclaredField("delegate")
        field.isAccessible = true
        return field.get(uploader) as com.apm.uploader.ApmUploader
    }

    /**
     * Reads the private gzip flag for factory wiring verification.
     *
     * @param uploader HTTP uploader created by the factory
     * @return configured gzip flag
     */
    private fun readGzipFlag(uploader: HttpApmUploader): Boolean {
        val field = HttpApmUploader::class.java.getDeclaredField("enableGzip")
        field.isAccessible = true
        return field.getBoolean(uploader)
    }
}
