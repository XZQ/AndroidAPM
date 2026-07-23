package com.apm.uploader

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
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

    /** Worker and delayed retry execution both use explicit daemon/background threads. */
    @Test
    fun `retry executors use governed background threads`() {
        val successLatch = CountDownLatch(1)
        val delegate = SequenceUploader(
            results = listOf(false, true),
            successLatch = successLatch
        )
        val uploader = RetryingApmUploader(
            delegate = delegate,
            retryPolicy = RetryPolicy(
                maxRetries = 1,
                baseDelayMs = RETRY_DELAY_MS,
                maxDelayMs = RETRY_DELAY_MS
            ),
            flushIntervalMs = FLUSH_INTERVAL_MS
        )

        uploader.upload(createEvent("thread_policy"))

        assertTrue(successLatch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(2, delegate.invocationThreads.size)
        assertThreadPolicy(delegate.invocationThreads[0], WORKER_THREAD_NAME)
        assertThreadPolicy(delegate.invocationThreads[1], RETRY_SCHEDULER_THREAD_NAME)

        uploader.shutdown()
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
     * Verifies one captured uploader invocation thread.
     *
     * @param snapshot immutable thread attributes captured inside the delegate
     * @param expectedName required stable SDK thread name
     */
    private fun assertThreadPolicy(snapshot: UploaderThreadSnapshot, expectedName: String) {
        assertEquals(expectedName, snapshot.name)
        assertTrue(snapshot.daemon)
        assertEquals(Thread.MIN_PRIORITY, snapshot.priority)
    }

    /**
     * 按顺序返回上传结果的 uploader。
     */
    private class SequenceUploader(private val results: List<Boolean>, private val successLatch: CountDownLatch? = null) : ApmUploader {

        /** 调用次数。 */
        val attempts = AtomicInteger(0)

        /** Actual executor-thread attributes for every delegate invocation. */
        val invocationThreads = CopyOnWriteArrayList<UploaderThreadSnapshot>()

        /**
         * 返回预设结果。
         *
         * @param event 待上传事件
         * @return 预设的成功或失败标记
         */
        override fun upload(event: ApmEvent): Boolean {
            // 每次上传都推进一次预设结果。
            val index = attempts.getAndIncrement()
            val currentThread = Thread.currentThread()
            // Capture inside the delegate so the test observes the real executor, not construction.
            invocationThreads += UploaderThreadSnapshot(
                name = currentThread.name,
                daemon = currentThread.isDaemon,
                priority = currentThread.priority
            )
            val result = results.getOrElse(index) { results.lastOrNull() ?: false }
            if (result) {
                successLatch?.countDown()
            }
            return result
        }
    }

    /** Immutable executor-thread attributes captured during a delegate call. */
    private data class UploaderThreadSnapshot(
        /** Actual thread name. */
        val name: String,
        /** Whether the thread is daemon-governed. */
        val daemon: Boolean,
        /** Java scheduling priority. */
        val priority: Int
    )

    companion object {
        /** 快速重试延迟。 */
        private const val RETRY_DELAY_MS = 5L

        /** 快速 flush 间隔。 */
        private const val FLUSH_INTERVAL_MS = 5L

        /** 等待重试成功的超时秒数。 */
        private const val AWAIT_TIMEOUT_SECONDS = 2L

        /** Expected main in-memory retry worker name. */
        private const val WORKER_THREAD_NAME = "apm-upload-retry"

        /** Expected delayed retry scheduler name. */
        private const val RETRY_SCHEDULER_THREAD_NAME = "apm-upload-retry-scheduler"
    }
}
