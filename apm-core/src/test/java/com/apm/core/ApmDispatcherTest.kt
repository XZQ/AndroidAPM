package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.storage.EventStore
import com.apm.uploader.ApmUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ApmDispatcher 行为测试。
 * 验证关闭后的分发器不会再接收新事件。
 */
class ApmDispatcherTest {

    /** shutdown 之后的事件应被直接忽略。 */
    @Test
    fun `dispatch ignores events after shutdown`() {
        val store = RecordingStore()
        val uploader = RecordingUploader()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = uploader,
            logger = RecordingLogger()
        )

        dispatcher.shutdown()
        dispatcher.dispatch(createEvent(name = "after_shutdown"))
        Thread.sleep(WAIT_BRIEFLY_MS)

        assertTrue(store.events.isEmpty())
        assertTrue(uploader.events.isEmpty())
    }

    /** 正常关闭前的事件仍应被处理。 */
    @Test
    fun `dispatch processes event before shutdown`() {
        val latch = CountDownLatch(1)
        val store = RecordingStore()
        val uploader = RecordingUploader(latch)
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = uploader,
            logger = RecordingLogger()
        )

        dispatcher.dispatch(createEvent(name = "before_shutdown"))

        assertTrue(latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, store.events.size)
        assertEquals(1, uploader.events.size)

        dispatcher.shutdown()
    }

    /**
     * 构造测试事件。
     *
     * @param name 事件名
     * @return 标准 APM 事件
     */
    private fun createEvent(name: String): ApmEvent {
        return ApmEvent(
            module = "core",
            name = name,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            processName = "process",
            threadName = "main"
        )
    }

    /**
     * 记录型存储实现。
     */
    private class RecordingStore : EventStore {

        /** 已追加事件。 */
        val events = mutableListOf<ApmEvent>()

        /**
         * 记录事件。
         *
         * @param event 待存储事件
         */
        override fun append(event: ApmEvent) {
            // 记录分发器实际落盘的事件。
            events += event
        }

        /**
         * 返回空列表。
         *
         * @param limit 最大条数
         * @return 空列表
         */
        override fun readRecent(limit: Int): List<String> = emptyList()

        /** 清空记录。 */
        override fun clear() {
            // 测试场景无需额外动作。
            events.clear()
        }
    }

    /**
     * 记录型上传器。
     */
    private class RecordingUploader(
        /** 成功上传后的同步信号。 */
        private val latch: CountDownLatch? = null
    ) : ApmUploader {

        /** 已上传事件。 */
        val events = mutableListOf<ApmEvent>()

        /**
         * 记录上传事件。
         *
         * @param event 待上传事件
         * @return 始终返回 true
         */
        override fun upload(event: ApmEvent): Boolean {
            // 记录上传结果，便于断言关闭前后行为。
            events += event
            latch?.countDown()
            return true
        }
    }

    /**
     * 空日志实现。
     */
    private class RecordingLogger : ApmLogger {

        /**
         * 忽略 debug 日志。
         *
         * @param message 日志内容
         */
        override fun d(message: String) = Unit

        /**
         * 忽略 warn 日志。
         *
         * @param message 日志内容
         */
        override fun w(message: String) = Unit

        /**
         * 忽略 error 日志。
         *
         * @param message 日志内容
         * @param throwable 异常
         */
        override fun e(message: String, throwable: Throwable?) = Unit
    }

    companion object {
        /** 异步断言前的短暂等待。 */
        private const val WAIT_BRIEFLY_MS = 100L

        /** 等待异步上传完成的超时秒数。 */
        private const val AWAIT_TIMEOUT_SECONDS = 2L
    }
}
