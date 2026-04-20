package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.uploader.HttpApmUploader
import com.apm.uploader.LogcatApmUploader
import com.apm.uploader.RetryingApmUploader
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
}
