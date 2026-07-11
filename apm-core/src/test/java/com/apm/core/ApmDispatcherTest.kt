package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import com.apm.core.privacy.DefaultSanitizationRules
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.EventStore
import com.apm.uploader.ApmUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    /** 存储和上传前应先执行 PII 脱敏。 */
    @Test
    fun `dispatch sanitizes event before store and upload`() {
        val latch = CountDownLatch(1)
        val store = RecordingStore()
        val uploader = RecordingUploader(latch)
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = uploader,
            logger = RecordingLogger(),
            piiSanitizer = PiiSanitizer(DefaultSanitizationRules.all())
        )

        dispatcher.dispatch(createEvent(name = "pii", fields = mapOf("phone" to RAW_PHONE)))

        assertTrue(latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(MASKED_PHONE, store.events.single().fields["phone"])
        assertEquals(MASKED_PHONE, uploader.events.single().fields["phone"])

        dispatcher.shutdown()
    }

    /** uploader 拒绝事件时应计入 SDK 自监控丢弃数。 */
    @Test
    fun `dispatch records drop when uploader rejects event`() {
        val latch = CountDownLatch(1)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = RecordingStore(),
            uploader = RejectingUploader(latch),
            logger = RecordingLogger()
        )
        dispatcher.selfMonitor = selfMonitor

        dispatcher.dispatch(createEvent(name = "reject", priority = ApmPriority.HIGH))

        assertTrue(latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        waitUntil { selfMonitor.getTotalDropCount() == 1L }
        assertEquals(1L, selfMonitor.getTotalDropCount())

        dispatcher.shutdown()
    }

    /** A recoverable event factory failure must not terminate the shared dispatcher worker. */
    @Test
    fun `recoverable lazy factory failure does not kill dispatcher worker`() {
        val uploaded = CountDownLatch(1)
        val store = RecordingStore()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(uploaded),
            logger = RecordingLogger()
        )

        dispatcher.dispatchLazy { throw IllegalStateException("factory failure") }
        dispatcher.dispatch(createEvent(name = "after_failure"))

        assertTrue(uploaded.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf("after_failure"), store.events.map(ApmEvent::name))
        dispatcher.shutdown()
    }

    /** Fatal VM errors must remain visible instead of being converted into a telemetry drop. */
    @Test
    fun `critical persistence does not swallow fatal vm error`() {
        val fatal = OutOfMemoryError("fatal")
        val dispatcher = ApmDispatcher(
            store = ThrowingAppendStore(fatal),
            uploader = RecordingUploader(),
            logger = RecordingLogger()
        )

        val actual = assertThrows(OutOfMemoryError::class.java) {
            dispatcher.dispatchCriticalSync(createEvent(name = "fatal"))
        }

        assertSame(fatal, actual)
        dispatcher.shutdown()
    }

    /** A recoverable aggregation maintenance failure must allow the next scheduled invocation. */
    @Test
    fun `aggregation maintenance continues after recoverable failure`() {
        var attempts = 0
        val emitted = mutableListOf<ApmEvent>()
        val errors = mutableListOf<Exception>()
        val flush = {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("flush")
            listOf(createEvent("recovered"))
        }

        runAggregationMaintenance(flush, emitted::add, errors::add)
        runAggregationMaintenance(flush, emitted::add, errors::add)

        assertEquals(listOf("recovered"), emitted.map(ApmEvent::name))
        assertEquals("flush", errors.single().message)
    }

    /**
     * 构造测试事件。
     *
     * @param name 事件名
     * @return 标准 APM 事件
     */
    private fun createEvent(name: String, priority: ApmPriority = ApmPriority.NORMAL, fields: Map<String, Any?> = emptyMap()): ApmEvent {
        return ApmEvent(
            module = "core",
            name = name,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            priority = priority,
            processName = "process",
            threadName = "main",
            fields = fields
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

    /** Store that exposes whether dispatcher recovery code swallows a fatal error. */
    private class ThrowingAppendStore(
        /** Fatal error thrown from local persistence. */
        private val fatal: Error
    ) : EventStore {

        /** Throws the configured fatal error. */
        override fun append(event: ApmEvent) {
            throw fatal
        }

        /** No persisted rows are available. */
        override fun readRecent(limit: Int): List<String> = emptyList()

        /** Nothing is retained by this test store. */
        override fun clear() = Unit
    }

    /**
     * 记录型上传器。
     */
    private class RecordingUploader(private val latch: CountDownLatch? = null) : ApmUploader {

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
     * 拒绝型上传器。
     */
    private class RejectingUploader(private val latch: CountDownLatch) : ApmUploader {

        /**
         * 拒绝上传事件。
         *
         * @param event 待上传事件
         * @return 始终返回 false
         */
        override fun upload(event: ApmEvent): Boolean {
            // 明确触发 dispatcher 的 uploader rejected 分支。
            latch.countDown()
            return false
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

    /**
     * 等待异步断言条件成立。
     *
     * @param predicate 需要满足的条件。
     */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_SECONDS * MILLIS_PER_SECOND
        while (System.currentTimeMillis() < deadline) {
            // dispatcher 在后台线程更新自监控，测试侧短轮询等待可见结果。
            if (predicate()) {
                return
            }
            Thread.sleep(WAIT_POLL_INTERVAL_MS)
        }
    }

    companion object {
        /** 异步断言前的短暂等待。 */
        private const val WAIT_BRIEFLY_MS = 100L

        /** 等待异步上传完成的超时秒数。 */
        private const val AWAIT_TIMEOUT_SECONDS = 2L

        /** 秒到毫秒换算。 */
        private const val MILLIS_PER_SECOND = 1000L

        /** 异步断言轮询间隔。 */
        private const val WAIT_POLL_INTERVAL_MS = 10L

        /** 脱敏前手机号。 */
        private const val RAW_PHONE = "13812345678"

        /** 脱敏后手机号。 */
        private const val MASKED_PHONE = "138****5678"
    }
}
