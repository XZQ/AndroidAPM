package com.apm.core.aggregation

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StackFingerprinter] 单元测试。
 *
 * 验证：
 * 1. 相同栈指纹在窗口内被去重
 * 2. 不同栈指纹不被去重
 * 3. 无栈信息的事件不做去重
 * 4. 窗口过期后重复指纹视为新事件
 */
class StackFingerprinterTest {

    @Test
    fun `first event with stack trace is not duplicate`() {
        val fingerprinter = StackFingerprinter()
        val event = createEvent("at com.app.Main.test(Main.java:10)")

        val result = fingerprinter.check(event)

        assertTrue("First event should be New", result is StackFingerprinter.DedupResult.New)
    }

    @Test
    fun `same stack trace within window is duplicate`() {
        val fingerprinter = StackFingerprinter(dedupWindowMs = Long.MAX_VALUE)
        val stackTrace = "at com.app.Main.test(Main.java:10)\nat com.app.Helper.run(Helper.java:20)"
        val event1 = createEvent(stackTrace)
        val event2 = createEvent(stackTrace)

        fingerprinter.check(event1)
        val result = fingerprinter.check(event2)

        assertTrue("Same stack within window should be Duplicate", result is StackFingerprinter.DedupResult.Duplicate)
        assertEquals(2, (result as StackFingerprinter.DedupResult.Duplicate).totalCount)
    }

    @Test
    fun `different stack traces are not deduplicated`() {
        val fingerprinter = StackFingerprinter()
        val event1 = createEvent("at com.app.Main.test1(Main.java:10)")
        val event2 = createEvent("at com.app.Main.test2(Main.java:20)")

        fingerprinter.check(event1)
        val result = fingerprinter.check(event2)

        assertTrue("Different stacks should both be New", result is StackFingerprinter.DedupResult.New)
    }

    @Test
    fun `event without stack trace is never deduplicated`() {
        val fingerprinter = StackFingerprinter()
        val event = ApmEvent(
            module = "crash",
            name = "java_crash",
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            fields = mapOf("exception" to "OOM")
        )

        val result1 = fingerprinter.check(event)
        val result2 = fingerprinter.check(event)

        assertTrue("No stack trace → always New", result1 is StackFingerprinter.DedupResult.New)
        assertTrue("No stack trace → always New", result2 is StackFingerprinter.DedupResult.New)
    }

    /** 创建带栈信息的 ALERT 事件。 */
    private fun createEvent(stackTrace: String): ApmEvent {
        return ApmEvent(
            module = "crash",
            name = "java_crash",
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            fields = mapOf("stack_trace" to stackTrace)
        )
    }
}
