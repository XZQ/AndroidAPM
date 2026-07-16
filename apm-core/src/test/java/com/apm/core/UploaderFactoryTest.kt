package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.core.throttle.DynamicConfigProvider
import com.apm.uploader.HttpEndpointProvider
import com.apm.uploader.HttpApmUploader
import com.apm.uploader.LogcatApmUploader
import com.apm.uploader.RetryingApmUploader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UploaderFactory 上传器选择测试。
 * 验证 endpoint、自定义 uploader 和重试包装的决策逻辑。
 */
class UploaderFactoryTest {

    /** 自定义 uploader 应优先于 endpoint 规则。 */
    @Test
    fun `custom uploader takes precedence over endpoint`() {
        val customUploader = RecordingUploader()
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            uploader = customUploader,
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        assertSame(customUploader, uploader)
    }

    /** HTTP endpoint 应默认创建 HttpApmUploader。 */
    @Test
    fun `http endpoint uses http uploader`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        assertTrue(uploader is HttpApmUploader)
    }

    /** 默认 HTTP uploader 应跟随 ApmConfig 开启 Gzip。 */
    @Test
    fun `http endpoint enables gzip by default`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        assertTrue(uploader is HttpApmUploader)
        assertTrue(readGzipFlag(uploader as HttpApmUploader))
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

        assertTrue(uploader is HttpApmUploader)
        assertFalse(readGzipFlag(uploader as HttpApmUploader))
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

        val uploader = UploaderFactory.create(config) as HttpApmUploader
        val field = HttpApmUploader::class.java.getDeclaredField("endpointProvider")
        field.isAccessible = true
        val provider = field.get(uploader) as HttpEndpointProvider

        assertTrue(
            provider.currentEndpoint(config.endpoint) == "https://collector.example/v1/events"
        )
    }

    /** 空 endpoint 应回落到 Logcat uploader。 */
    @Test
    fun `blank endpoint uses logcat uploader`() {
        val config = ApmConfig(
            endpoint = "",
            enableRetry = false
        )

        val uploader = UploaderFactory.create(config)

        assertTrue(uploader is LogcatApmUploader)
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
