package com.apm.uploader

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * RetryingApmUploader 重试与关闭测试。
 */
class RetryingApmUploaderTest {

    /** delegate 返回 false 时应继续重试直到成功。 */
    @Test
    fun `retries when delegate reports failure`() {
        val successLatch = CountDownLatch(1)
        val delegate = SequenceUploader(
            results = listOf(false, false, true),
            successLatch = successLatch
        )
        val uploader = RetryingApmUploader(
            delegate = delegate,
            retryPolicy = RetryPolicy(
                maxRetries = 3,
                baseDelayMs = RETRY_DELAY_MS,
                maxDelayMs = RETRY_DELAY_MS
            ),
            flushIntervalMs = FLUSH_INTERVAL_MS
        )

        uploader.upload(createEvent("retry_me"))

        assertTrue(successLatch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(3, delegate.attempts.get())

        uploader.shutdown()
    }

    /** 关闭后不应再接收新事件。 */
    @Test
    fun `upload returns false after shutdown`() {
        val uploader = RetryingApmUploader(
            delegate = SequenceUploader(results = listOf(true)),
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS, maxDelayMs = RETRY_DELAY_MS),
            flushIntervalMs = FLUSH_INTERVAL_MS
        )

        uploader.shutdown()

        assertFalse(uploader.upload(createEvent("after_stop")))
    }

    /**
     * 构造测试事件。
     *
     * @param name 事件名
     * @return 测试用 APM 事件
     */
    private fun createEvent(name: String): ApmEvent {
        return ApmEvent(
            module = "uploader",
            name = name,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            processName = "process",
            threadName = "worker"
        )
    }

    /**
     * 按顺序返回上传结果的 uploader。
     */
    private class SequenceUploader(
        /** 每次上传的期望结果。 */
        private val results: List<Boolean>,
        /** 成功后发出的完成信号。 */
        private val successLatch: CountDownLatch? = null
    ) : ApmUploader {

        /** 调用次数。 */
        val attempts = AtomicInteger(0)

        /**
         * 返回预设结果。
         *
         * @param event 待上传事件
         * @return 预设的成功或失败标记
         */
        override fun upload(event: ApmEvent): Boolean {
            // 每次上传都推进一次预设结果。
            val index = attempts.getAndIncrement()
            val result = results.getOrElse(index) { results.lastOrNull() ?: false }
            if (result) {
                successLatch?.countDown()
            }
            return result
        }
    }

    companion object {
        /** 快速重试延迟。 */
        private const val RETRY_DELAY_MS = 5L

        /** 快速 flush 间隔。 */
        private const val FLUSH_INTERVAL_MS = 5L

        /** 等待重试成功的超时秒数。 */
        private const val AWAIT_TIMEOUT_SECONDS = 2L
    }
}
