package com.apm.slowmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApmSlowMethodTracer 单元测试。
 *
 * Tracer 是 ASM 插桩运行时的全局单例，Apm.emit 在未初始化时是 no-op，
 * 因此本测试聚焦确定性核心逻辑：enter/exit 配对与失配 unwind、嵌套调用、
 * 热点统计与容量上限、启用开关以及线程隔离。
 * 每个用例结束后清理全局状态，避免跨用例污染。
 */
class ApmSlowMethodTracerTest {

    /** 每个用例后复位全局单例状态。 */
    @After
    fun tearDown() {
        ApmSlowMethodTracer.disable()
        ApmSlowMethodTracer.clearHotMethods()
    }

    /** enter/exit 配对后热点统计记录一次命中与正耗时。 */
    @Test
    fun `enter exit pair records hot method`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)

        ApmSlowMethodTracer.methodEnter(METHOD_A)
        Thread.sleep(SHORT_SLEEP_MS)
        ApmSlowMethodTracer.methodExit(METHOD_A)

        val hot = ApmSlowMethodTracer.getHotMethods()
        assertEquals(1, hot.size)
        assertEquals(METHOD_A, hot.single().methodSignature)
        assertEquals(1, hot.single().hitCount.get())
        assertTrue(hot.single().totalDurationMs.get() >= MIN_RECORDED_DURATION_MS)
    }

    /** 嵌套调用各自配对，互不吞并。 */
    @Test
    fun `nested calls pair independently`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)

        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodEnter(METHOD_B)
        ApmSlowMethodTracer.methodExit(METHOD_B)
        ApmSlowMethodTracer.methodExit(METHOD_A)

        val hot = ApmSlowMethodTracer.getHotMethods().associateBy { it.methodSignature }
        assertEquals(2, hot.size)
        assertEquals(1, hot.getValue(METHOD_A).hitCount.get())
        assertEquals(1, hot.getValue(METHOD_B).hitCount.get())
    }

    /** 失配 exit 会清空本线程全部悬挂帧，后续配对 exit 不再生效。 */
    @Test
    fun `unmatched exit clears stale frames`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)

        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodEnter(METHOD_B)
        // exit 一个从未 enter 的签名：pop 到栈底也没有匹配，全部悬挂帧被丢弃。
        ApmSlowMethodTracer.methodExit(METHOD_C)

        assertTrue(ApmSlowMethodTracer.getHotMethods().isEmpty())

        // 悬挂帧已清空，补发的 exit 是 no-op。
        ApmSlowMethodTracer.methodExit(METHOD_A)
        assertTrue(ApmSlowMethodTracer.getHotMethods().isEmpty())
    }

    /** 空栈上的 exit 是 no-op。 */
    @Test
    fun `exit on empty stack is no-op`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)

        ApmSlowMethodTracer.methodExit(METHOD_A)

        assertTrue(ApmSlowMethodTracer.getHotMethods().isEmpty())
    }

    /** init 之前（默认禁用）enter/exit 均为 no-op。 */
    @Test
    fun `tracer is disabled before init`() {
        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodExit(METHOD_A)

        assertTrue(ApmSlowMethodTracer.getHotMethods().isEmpty())
    }

    /** disable 之后 enter/exit 不再记录。 */
    @Test
    fun `disabled tracer is no-op`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)
        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodExit(METHOD_A)
        assertEquals(1, ApmSlowMethodTracer.getHotMethods().size)

        ApmSlowMethodTracer.disable()
        ApmSlowMethodTracer.methodEnter(METHOD_B)
        ApmSlowMethodTracer.methodExit(METHOD_B)

        assertEquals(1, ApmSlowMethodTracer.getHotMethods().size)
    }

    /** 热点方法表达到上限后拒绝新方法，既有方法继续累计。 */
    @Test
    fun `max hot methods caps new entries but existing ones accumulate`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = 2)

        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodExit(METHOD_A)
        ApmSlowMethodTracer.methodEnter(METHOD_B)
        ApmSlowMethodTracer.methodExit(METHOD_B)
        // 已满：第三个新方法被拒绝。
        ApmSlowMethodTracer.methodEnter(METHOD_C)
        ApmSlowMethodTracer.methodExit(METHOD_C)
        assertEquals(2, ApmSlowMethodTracer.getHotMethods().size)

        // 既有方法不受上限影响。
        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodExit(METHOD_A)
        val hot = ApmSlowMethodTracer.getHotMethods().associateBy { it.methodSignature }
        assertEquals(2, hot.size)
        assertEquals(2, hot.getValue(METHOD_A).hitCount.get())
    }

    /** getHotMethods 按命中次数降序。 */
    @Test
    fun `hot methods sorted by hit count descending`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)

        repeat(3) {
            ApmSlowMethodTracer.methodEnter(METHOD_A)
            ApmSlowMethodTracer.methodExit(METHOD_A)
        }
        ApmSlowMethodTracer.methodEnter(METHOD_B)
        ApmSlowMethodTracer.methodExit(METHOD_B)

        val hot = ApmSlowMethodTracer.getHotMethods()
        assertEquals(METHOD_A, hot.first().methodSignature)
        assertEquals(3, hot.first().hitCount.get())
        assertEquals(METHOD_B, hot.last().methodSignature)
    }

    /** clearHotMethods 清空全部统计。 */
    @Test
    fun `clearHotMethods resets statistics`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)
        ApmSlowMethodTracer.methodEnter(METHOD_A)
        ApmSlowMethodTracer.methodExit(METHOD_A)

        ApmSlowMethodTracer.clearHotMethods()

        assertTrue(ApmSlowMethodTracer.getHotMethods().isEmpty())
    }

    /** 各线程的 enter 栈相互隔离：另一线程的悬挂帧不影响本线程配对。 */
    @Test
    fun `per-thread stacks are isolated`() {
        ApmSlowMethodTracer.init(thresholdMs = NEVER_THRESHOLD_MS, maxHotMethods = DEFAULT_MAX_HOT_METHODS)

        // 其他线程留下一个悬挂 enter 后退出（不调用 exit）。
        val leaker = Thread {
            ApmSlowMethodTracer.methodEnter(METHOD_A)
        }
        leaker.join()

        // 本线程不受污染，正常配对。
        ApmSlowMethodTracer.methodEnter(METHOD_B)
        ApmSlowMethodTracer.methodExit(METHOD_B)

        val hot = ApmSlowMethodTracer.getHotMethods().associateBy { it.methodSignature }
        assertEquals(1, hot.size)
        assertEquals(1, hot.getValue(METHOD_B).hitCount.get())
    }

    companion object {
        /** 方法 A 签名。 */
        private const val METHOD_A = "com.example.OrderService#checkout"

        /** 方法 B 签名。 */
        private const val METHOD_B = "com.example.CartService#addItem"

        /** 方法 C 签名（从不 enter）。 */
        private const val METHOD_C = "com.example.StockService#reserve"

        /** 永不触发的上报阈值（毫秒）。 */
        private const val NEVER_THRESHOLD_MS = 60_000L

        /** 测试用热点容量上限。 */
        private const val DEFAULT_MAX_HOT_METHODS = 100

        /** 使耗时可观测的最短睡眠（毫秒）。 */
        private const val SHORT_SLEEP_MS = 15L

        /** 断言下限：真实时钟下 15ms 睡眠至少记录到 10ms。 */
        private const val MIN_RECORDED_DURATION_MS = 10L
    }
}
