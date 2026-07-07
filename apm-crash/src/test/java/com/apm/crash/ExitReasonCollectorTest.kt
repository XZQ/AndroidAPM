package com.apm.crash

import com.apm.model.ApmSeverity
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExitReasonCollector] 采集逻辑测试。
 * 通过 [ExitInfoSource]/[ExitTimestampStore] 替身在 JVM 上验证
 * 去重、原因映射、严重级别与 trace 截断。
 */
class ExitReasonCollectorTest {

    /** 内存版时间戳存储。 */
    private class FakeTimestampStore(
        /** 初始时间戳。 */
        var value: Long = 0L
    ) : ExitTimestampStore {
        override fun lastProcessedMs(): Long = value
        override fun saveLastProcessedMs(value: Long) {
            this.value = value
        }
    }

    /** 收集 emit 调用的记录器。 */
    private class EmitRecorder {
        /** 已上报事件：(名称, 严重级别, 字段)。 */
        val emitted = mutableListOf<Triple<String, ApmSeverity, Map<String, Any?>>>()

        /** emit 回调。 */
        fun emit(name: String, severity: ApmSeverity, fields: Map<String, Any?>) {
            emitted += Triple(name, severity, fields)
        }
    }

    /** 新记录被上报且处理位置推进。 */
    @Test
    fun `new exit records are emitted and timestamp advances`() {
        val store = FakeTimestampStore()
        val recorder = EmitRecorder()
        val collector = ExitReasonCollector(
            source = {
                listOf(
                    ExitRecord(timestampMs = 1_000L, reasonCode = REASON_CRASH, description = "crash", importance = 100),
                    ExitRecord(timestampMs = 2_000L, reasonCode = REASON_LOW_MEMORY, description = null, importance = 400)
                )
            },
            timestampStore = store,
            emit = recorder::emit
        )

        collector.collectOnce()

        assertEquals(2, recorder.emitted.size)
        assertEquals(2_000L, store.value)
        // 按时间升序上报
        assertEquals("CRASH", recorder.emitted[0].third["reasonName"])
        assertEquals("LOW_MEMORY", recorder.emitted[1].third["reasonName"])
    }

    /** 已处理时间戳之前的记录被去重跳过。 */
    @Test
    fun `already processed records are deduplicated`() {
        val store = FakeTimestampStore(value = 1_500L)
        val recorder = EmitRecorder()
        val collector = ExitReasonCollector(
            source = {
                listOf(
                    ExitRecord(timestampMs = 1_000L, reasonCode = REASON_CRASH, description = null, importance = 100),
                    ExitRecord(timestampMs = 2_000L, reasonCode = ExitReasonCollector.REASON_ANR, description = null, importance = 100)
                )
            },
            timestampStore = store,
            emit = recorder::emit
        )

        collector.collectOnce()

        // 仅 2000ms 的新记录被上报
        assertEquals(1, recorder.emitted.size)
        assertEquals("ANR", recorder.emitted.single().third["reasonName"])
        assertEquals(2_000L, store.value)
    }

    /** 崩溃/ANR 映射 ERROR，低内存映射 WARN，自然退出映射 INFO。 */
    @Test
    fun `severity is mapped by reason code`() {
        val recorder = EmitRecorder()
        val collector = ExitReasonCollector(
            source = {
                listOf(
                    ExitRecord(timestampMs = 1L, reasonCode = ExitReasonCollector.REASON_ANR, description = null, importance = 0),
                    ExitRecord(timestampMs = 2L, reasonCode = REASON_LOW_MEMORY, description = null, importance = 0),
                    ExitRecord(timestampMs = 3L, reasonCode = REASON_EXIT_SELF, description = null, importance = 0)
                )
            },
            timestampStore = FakeTimestampStore(),
            emit = recorder::emit
        )

        collector.collectOnce()

        assertEquals(ApmSeverity.ERROR, recorder.emitted[0].second)
        assertEquals(ApmSeverity.WARN, recorder.emitted[1].second)
        assertEquals(ApmSeverity.INFO, recorder.emitted[2].second)
    }

    /** ANR 记录附带的 trace 被截断到 maxTraceBytes。 */
    @Test
    fun `anr trace is attached and truncated`() {
        val longTrace = "x".repeat(1_000)
        val recorder = EmitRecorder()
        val collector = ExitReasonCollector(
            source = {
                listOf(
                    ExitRecord(
                        timestampMs = 1L,
                        reasonCode = ExitReasonCollector.REASON_ANR,
                        description = null,
                        importance = 0,
                        traceSupplier = { ByteArrayInputStream(longTrace.toByteArray()) }
                    )
                )
            },
            timestampStore = FakeTimestampStore(),
            maxTraceBytes = TRACE_LIMIT_BYTES,
            emit = recorder::emit
        )

        collector.collectOnce()

        val trace = recorder.emitted.single().third["trace"] as String
        assertEquals(TRACE_LIMIT_BYTES, trace.length)
    }

    /** 非 ANR 记录不读取 trace。 */
    @Test
    fun `non anr records carry no trace`() {
        val recorder = EmitRecorder()
        val collector = ExitReasonCollector(
            source = {
                listOf(
                    ExitRecord(timestampMs = 1L, reasonCode = REASON_CRASH, description = null, importance = 0)
                )
            },
            timestampStore = FakeTimestampStore(),
            emit = recorder::emit
        )

        collector.collectOnce()

        assertNull(recorder.emitted.single().third["trace"])
    }

    /** 无新记录时不写时间戳存储。 */
    @Test
    fun `no new records leaves timestamp untouched`() {
        val store = FakeTimestampStore(value = 5_000L)
        val recorder = EmitRecorder()
        val collector = ExitReasonCollector(
            source = { emptyList() },
            timestampStore = store,
            emit = recorder::emit
        )

        collector.collectOnce()

        assertTrue(recorder.emitted.isEmpty())
        assertEquals(5_000L, store.value)
    }

    companion object {
        /** REASON_CRASH（与 ApplicationExitInfo 对齐）。 */
        private const val REASON_CRASH = 4

        /** REASON_LOW_MEMORY。 */
        private const val REASON_LOW_MEMORY = 3

        /** REASON_EXIT_SELF。 */
        private const val REASON_EXIT_SELF = 1

        /** trace 截断上限（字节）。 */
        private const val TRACE_LIMIT_BYTES = 128
    }
}
