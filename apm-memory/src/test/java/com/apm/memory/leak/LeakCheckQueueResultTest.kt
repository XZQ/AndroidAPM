package com.apm.memory.leak

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * LeakCheckQueue 交付路径测试。
 *
 * 生命周期界限、GC 节流与取消语义由 LifecycleLeakDetectorTest 覆盖；
 * 本文件覆盖 retained 目标的 LeakResult 交付内容：
 * suspectFields 出站字段提示、字段提示上限、以及 sink 异常隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LeakCheckQueueResultTest {

    /** 强引用存活的泄漏目标交付带出站字段提示的完整结果。 */
    @Test
    fun `retained target delivers leak result with suspect field hints`() {
        val results = ArrayList<LeakResult>()
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { }
        // 目标被本测试强引用持有，确定性判定为泄漏
        val target = HandlerHolder(Handler(Looper.getMainLooper()))
        try {
            checks.watch(target, LeakType.CUSTOM, TEST_SCENE, this) { results.add(it) }
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(CHECK_DELAY_MS))
            val result = results.single()
            // 类名、类型与场景在销毁现场同步捕获
            assertEquals(HandlerHolder::class.java.name, result.leakClass)
            assertEquals(LeakType.CUSTOM, result.type)
            assertEquals(TEST_SCENE, result.scene)
            assertEquals(1, result.retainedCount)
            // 轻量检测只填出站字段提示，不伪造 GC Root 引用链
            assertTrue(result.suspectFields.size == 1)
            assertTrue(result.suspectFields.single().contains("handler"))
            assertTrue(result.referenceChain.isEmpty())
        } finally {
            checks.shutdown()
        }
    }

    /** 出站字段提示数量受固定上限约束。 */
    @Test
    fun `field hints stay bounded under saturated holder fields`() {
        val results = ArrayList<LeakResult>()
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { }
        val handler = Handler(Looper.getMainLooper())
        val target = SaturatedHandlerHolder(handler)
        try {
            checks.watch(target, LeakType.CUSTOM, TEST_SCENE, this) { results.add(it) }
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(CHECK_DELAY_MS))
            // 33 个疑似字段超过 32 条提示上限，超出部分被截断
            assertEquals(MAX_FIELD_HINTS, results.single().suspectFields.size)
        } finally {
            checks.shutdown()
        }
    }

    /** sink 抛出异常时不向队列线程传播，且水位正常清空。 */
    @Test
    fun `failing sink is isolated from the queue worker`() {
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { }
        val target = HandlerHolder(Handler(Looper.getMainLooper()))
        try {
            checks.watch(target, LeakType.CUSTOM, TEST_SCENE, this) { throw IllegalStateException(BOOM) }
            // onLeak 异常被捕获并记录自监控，不得逃逸到后台线程
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(CHECK_DELAY_MS))
            assertEquals(0, checks.pendingCount())
        } finally {
            checks.shutdown()
        }
    }

    /** 单个 Handler 字段的泄漏目标。 */
    private class HandlerHolder(handler: Handler) {
        /** 疑似持有泄漏源。 */
        val handler: Handler = handler
    }

    /** 33 个 Handler 字段的泄漏目标，用于触发提示上限。 */
    @Suppress("unused")
    private class SaturatedHandlerHolder(handler: Handler) {
        /** 疑似持有槽位 00。 */
        val h00: Handler = handler
        /** 疑似持有槽位 01。 */
        val h01: Handler = handler
        /** 疑似持有槽位 02。 */
        val h02: Handler = handler
        /** 疑似持有槽位 03。 */
        val h03: Handler = handler
        /** 疑似持有槽位 04。 */
        val h04: Handler = handler
        /** 疑似持有槽位 05。 */
        val h05: Handler = handler
        /** 疑似持有槽位 06。 */
        val h06: Handler = handler
        /** 疑似持有槽位 07。 */
        val h07: Handler = handler
        /** 疑似持有槽位 08。 */
        val h08: Handler = handler
        /** 疑似持有槽位 09。 */
        val h09: Handler = handler
        /** 疑似持有槽位 10。 */
        val h10: Handler = handler
        /** 疑似持有槽位 11。 */
        val h11: Handler = handler
        /** 疑似持有槽位 12。 */
        val h12: Handler = handler
        /** 疑似持有槽位 13。 */
        val h13: Handler = handler
        /** 疑似持有槽位 14。 */
        val h14: Handler = handler
        /** 疑似持有槽位 15。 */
        val h15: Handler = handler
        /** 疑似持有槽位 16。 */
        val h16: Handler = handler
        /** 疑似持有槽位 17。 */
        val h17: Handler = handler
        /** 疑似持有槽位 18。 */
        val h18: Handler = handler
        /** 疑似持有槽位 19。 */
        val h19: Handler = handler
        /** 疑似持有槽位 20。 */
        val h20: Handler = handler
        /** 疑似持有槽位 21。 */
        val h21: Handler = handler
        /** 疑似持有槽位 22。 */
        val h22: Handler = handler
        /** 疑似持有槽位 23。 */
        val h23: Handler = handler
        /** 疑似持有槽位 24。 */
        val h24: Handler = handler
        /** 疑似持有槽位 25。 */
        val h25: Handler = handler
        /** 疑似持有槽位 26。 */
        val h26: Handler = handler
        /** 疑似持有槽位 27。 */
        val h27: Handler = handler
        /** 疑似持有槽位 28。 */
        val h28: Handler = handler
        /** 疑似持有槽位 29。 */
        val h29: Handler = handler
        /** 疑似持有槽位 30。 */
        val h30: Handler = handler
        /** 疑似持有槽位 31。 */
        val h31: Handler = handler
        /** 疑似持有槽位 32。 */
        val h32: Handler = handler
    }

    companion object {
        /** 固定观察延迟。 */
        private const val CHECK_DELAY_MS = 5_000L

        /** 场景描述。 */
        private const val TEST_SCENE = "test"

        /** sink 异常消息。 */
        private const val BOOM = "boom"

        /** 出站字段提示上限，与 LeakCheckQueue.MAX_FIELD_HINTS 一致。 */
        private const val MAX_FIELD_HINTS = 32
    }
}
