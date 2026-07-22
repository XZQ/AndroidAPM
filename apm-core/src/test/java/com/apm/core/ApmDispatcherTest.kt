package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import com.apm.core.privacy.DefaultSanitizationRules
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.EventStore
import com.apm.storage.EventStoreAppendResult
import com.apm.uploader.ApmUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        assertEquals(REDACTED_PHONE, store.events.single().fields["phone"])
        assertEquals(REDACTED_PHONE, uploader.events.single().fields["phone"])

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
        assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.UPLOADER_REJECTED))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.HIGH))

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

    /** A full ingress queue must replace lower-value work instead of dropping a critical event. */
    @Test
    fun `critical lazy event evicts oldest lower priority event when queue is full`() {
        val firstAppendStarted = CountDownLatch(1)
        val releaseFirstAppend = CountDownLatch(1)
        val store = BlockingFirstAppendStore(firstAppendStarted, releaseFirstAppend)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(),
            logger = RecordingLogger(),
            selfMonitor = selfMonitor,
            queueCapacity = 2
        )

        dispatcher.dispatch(createEvent("blocking", priority = ApmPriority.NORMAL))
        assertTrue(firstAppendStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        dispatcher.dispatch(createEvent("low-oldest", priority = ApmPriority.LOW))
        dispatcher.dispatch(createEvent("low-newest", priority = ApmPriority.LOW))
        dispatcher.dispatchLazy(ApmPriority.CRITICAL) {
            createEvent("critical", priority = ApmPriority.CRITICAL)
        }

        releaseFirstAppend.countDown()
        dispatcher.shutdown()

        assertEquals(listOf("blocking", "low-newest", "critical"), store.events.map(ApmEvent::name))
        assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.DISPATCHER_PRIORITY_EVICTION))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.LOW))
    }

    /** A single event larger than the retained-byte budget must fail before queue visibility. */
    @Test
    fun `dispatcher rejects event larger than byte budget`() {
        val event = createEvent("oversized-memory", fields = mapOf("value" to "x".repeat(4_096)))
        val selfMonitor = SdkSelfMonitor()
        val store = RecordingStore()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(),
            logger = RecordingLogger(),
            selfMonitor = selfMonitor,
            maxQueuedBytes = ApmEventSizeEstimator.estimate(event) - 1L
        )

        dispatcher.dispatch(event)
        dispatcher.shutdown()

        assertTrue(store.events.isEmpty())
        assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.DISPATCHER_BYTE_BUDGET))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.NORMAL))
    }

    /** Byte pressure may evict multiple lower-priority events to preserve one critical signal. */
    @Test
    fun `critical event evicts enough low priority bytes`() {
        val firstAppendStarted = CountDownLatch(1)
        val releaseFirstAppend = CountDownLatch(1)
        val store = BlockingFirstAppendStore(firstAppendStarted, releaseFirstAppend)
        val lowOne = createEvent(
            "low-bytes-1",
            priority = ApmPriority.LOW,
            fields = mapOf("value" to "l".repeat(100))
        )
        val lowTwo = lowOne.copy(name = "low-bytes-2")
        val critical = createEvent(
            "critical-bytes",
            priority = ApmPriority.CRITICAL,
            fields = mapOf("value" to "c".repeat(800))
        )
        val lowBytes = ApmEventSizeEstimator.estimate(lowOne) + ApmEventSizeEstimator.estimate(lowTwo)
        val criticalBytes = ApmEventSizeEstimator.estimate(critical)
        val byteBudget = maxOf(lowBytes, criticalBytes) + 1L
        assertTrue(byteBudget < criticalBytes + minOf(
            ApmEventSizeEstimator.estimate(lowOne),
            ApmEventSizeEstimator.estimate(lowTwo)
        ))
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(),
            logger = RecordingLogger(),
            selfMonitor = selfMonitor,
            queueCapacity = 10,
            maxQueuedBytes = byteBudget
        )

        dispatcher.dispatch(createEvent("blocking"))
        assertTrue(firstAppendStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        dispatcher.dispatch(lowOne)
        dispatcher.dispatch(lowTwo)
        dispatcher.dispatch(critical)

        releaseFirstAppend.countDown()
        dispatcher.shutdown()

        assertEquals(listOf("blocking", "critical-bytes"), store.events.map(ApmEvent::name))
        assertEquals(2L, selfMonitor.getDropCount(SdkDropReason.DISPATCHER_PRIORITY_EVICTION))
        assertEquals(2L, selfMonitor.getDropCount(ApmPriority.LOW))
    }

    /** A noisy NORMAL module must leave pressured queue capacity for peers and critical work. */
    @Test
    fun `module isolation preserves shared capacity under queue pressure`() {
        val firstAppendStarted = CountDownLatch(1)
        val releaseFirstAppend = CountDownLatch(1)
        val store = BlockingFirstAppendStore(firstAppendStarted, releaseFirstAppend)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(),
            logger = RecordingLogger(),
            selfMonitor = selfMonitor,
            queueCapacity = 4,
            enableModuleIsolation = true,
            moduleIsolationHighWatermarkPercent = 75,
            maxModuleQueueSharePercent = 50
        )

        dispatcher.dispatch(createEvent("blocking", module = "core"))
        assertTrue(firstAppendStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        dispatcher.dispatch(createEvent("network-1", module = "network"))
        dispatcher.dispatch(createEvent("network-2", module = "network"))
        dispatcher.dispatch(createEvent("network-3", module = "network"))
        dispatcher.dispatch(createEvent("network-isolated", module = "network"))
        dispatcher.dispatch(createEvent("io-retained", module = "io"))
        dispatcher.dispatch(
            createEvent("network-high", priority = ApmPriority.HIGH, module = "network")
        )

        releaseFirstAppend.countDown()
        dispatcher.shutdown()

        assertEquals(
            listOf("blocking", "network-2", "network-3", "io-retained", "network-high"),
            store.events.map(ApmEvent::name)
        )
        assertEquals(2L, selfMonitor.getTotalDropCount())
        assertEquals(1L, selfMonitor.getTotalDispatcherModuleIsolationDropCount())
    }

    /** Disabling module isolation restores the existing full-capacity admission behavior. */
    @Test
    fun `module isolation can be disabled`() {
        val firstAppendStarted = CountDownLatch(1)
        val releaseFirstAppend = CountDownLatch(1)
        val store = BlockingFirstAppendStore(firstAppendStarted, releaseFirstAppend)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(),
            logger = RecordingLogger(),
            selfMonitor = selfMonitor,
            queueCapacity = 4,
            enableModuleIsolation = false
        )

        dispatcher.dispatch(createEvent("blocking", module = "core"))
        assertTrue(firstAppendStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        repeat(4) { index ->
            dispatcher.dispatch(createEvent("network-$index", module = "network"))
        }
        dispatcher.dispatch(createEvent("network-full", module = "network"))

        releaseFirstAppend.countDown()
        dispatcher.shutdown()

        assertEquals(
            listOf("blocking", "network-0", "network-1", "network-2", "network-3"),
            store.events.map(ApmEvent::name)
        )
        assertEquals(1L, selfMonitor.getTotalDropCount())
        assertEquals(0L, selfMonitor.getTotalDispatcherModuleIsolationDropCount())
    }

    /** Invalid percentage inputs are clamped before pressure admission decisions. */
    @Test
    fun `module isolation clamps percentage configuration`() {
        val firstAppendStarted = CountDownLatch(1)
        val releaseFirstAppend = CountDownLatch(1)
        val store = BlockingFirstAppendStore(firstAppendStarted, releaseFirstAppend)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = RecordingUploader(),
            logger = RecordingLogger(),
            selfMonitor = selfMonitor,
            queueCapacity = 4,
            moduleIsolationHighWatermarkPercent = 0,
            maxModuleQueueSharePercent = 100
        )

        dispatcher.dispatch(createEvent("blocking", module = "core"))
        assertTrue(firstAppendStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        dispatcher.dispatch(createEvent("network-retained", module = "network"))
        dispatcher.dispatch(createEvent("network-clamped", module = "network"))

        releaseFirstAppend.countDown()
        dispatcher.shutdown()

        assertEquals(
            listOf("blocking", "network-retained"),
            store.events.map(ApmEvent::name)
        )
        assertEquals(1L, selfMonitor.getTotalDispatcherModuleIsolationDropCount())
        assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.DISPATCHER_MODULE_ISOLATION))
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

    /** 存储层隔离拒绝必须返回失败并计入 SDK 自监控。 */
    @Test
    fun `critical persistence reports isolated storage rejection`() {
        val event = createEvent(name = "oversized", priority = ApmPriority.CRITICAL)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = ResultStore(
                EventStoreAppendResult(
                    acceptedEventCount = 0,
                    rejectedEvents = listOf(event)
                )
            ),
            uploader = RecordingUploader(),
            logger = RecordingLogger()
        )
        dispatcher.selfMonitor = selfMonitor

        assertTrue(!dispatcher.dispatchCriticalSync(event))
        assertEquals(1L, selfMonitor.getTotalDropCount())
        assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.STORAGE_PAYLOAD_REJECTED))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.CRITICAL))

        dispatcher.shutdown()
    }

    /** 容量淘汰数必须进入 SDK 自监控，不能静默丢失。 */
    @Test
    fun `critical persistence records storage capacity eviction`() {
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(
            store = ResultStore(
                EventStoreAppendResult(
                    acceptedEventCount = 1,
                    capacityEvictedEventCount = CAPACITY_EVICTION_COUNT,
                    capacityEvictedPriorityCounts = mapOf(
                        ApmPriority.LOW to 1,
                        ApmPriority.NORMAL to 1,
                        ApmPriority.HIGH to 1
                    )
                )
            ),
            uploader = RecordingUploader(),
            logger = RecordingLogger()
        )
        dispatcher.selfMonitor = selfMonitor

        assertTrue(dispatcher.dispatchCriticalSync(createEvent(name = "retained")))
        assertEquals(CAPACITY_EVICTION_COUNT.toLong(), selfMonitor.getTotalDropCount())
        assertEquals(CAPACITY_EVICTION_COUNT.toLong(), selfMonitor.getDropCount(SdkDropReason.STORAGE_CAPACITY_EVICTED))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.LOW))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.NORMAL))
        assertEquals(1L, selfMonitor.getDropCount(ApmPriority.HIGH))
        assertEquals(0L, selfMonitor.getUnattributedDropPriorityCount())

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
     * @param priority 事件优先级
     * @param fields 事件字段
     * @param module 来源模块
     * @return 标准 APM 事件
     */
    private fun createEvent(
        name: String,
        priority: ApmPriority = ApmPriority.NORMAL,
        fields: Map<String, Any?> = emptyMap(),
        module: String = "core"
    ): ApmEvent {
        return ApmEvent(
            module = module,
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

    /** Store returning a fixed append result for dispatcher observability tests. */
    private class ResultStore(
        /** Fixed result returned for every single-event append. */
        private val result: EventStoreAppendResult
    ) : EventStore {

        /** Legacy append is unused because the dispatcher requests a result. */
        override fun append(event: ApmEvent) = Unit

        /** Returns the configured result without retaining test data. */
        override fun appendWithResult(event: ApmEvent): EventStoreAppendResult = result

        /** No recent rows are retained. */
        override fun readRecent(limit: Int): List<String> = emptyList()

        /** Nothing is retained by this test store. */
        override fun clear() = Unit
    }

    /** Store that keeps the worker occupied while producer-side overflow is exercised. */
    private class BlockingFirstAppendStore(
        /** Signals that the worker reached the first append. */
        private val firstAppendStarted: CountDownLatch,
        /** Releases the first append after the test fills the ingress queue. */
        private val releaseFirstAppend: CountDownLatch
    ) : EventStore {
        /** Ensures only the first append blocks. */
        private val first = AtomicBoolean(true)

        /** Events persisted after admission and eviction decisions. */
        val events = mutableListOf<ApmEvent>()

        /** Blocks the first event, then records every accepted event. */
        override fun append(event: ApmEvent) {
            if (first.compareAndSet(true, false)) {
                firstAppendStarted.countDown()
                releaseFirstAppend.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            events += event
        }

        /** No recent textual view is needed. */
        override fun readRecent(limit: Int): List<String> = emptyList()

        /** Clears recorded events. */
        override fun clear() {
            events.clear()
        }
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
        /** Capacity drops returned by the fixed-result store. */
        private const val CAPACITY_EVICTION_COUNT = 3
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
        private const val REDACTED_PHONE = "***"
    }
}
