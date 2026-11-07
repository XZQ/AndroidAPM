package com.apm.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * FrameMetricsWindowAccumulator 补充单元测试。
 *
 * FrameWindowPolicyTest 已覆盖"保留最新样本"与"负值钳制"；
 * 本测试补充空窗口语义、snapshotAndReset 清零、reset 语义、
 * delayed 帧淘汰递减与容量校验。
 */
class FrameMetricsWindowAccumulatorTest {

    /** 空窗口 snapshot 返回 null。 */
    @Test
    fun `empty window snapshots to null`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 2)
        assertNull(accumulator.snapshotAndReset())
    }

    /** snapshotAndReset 之后窗口逻辑清零，再次 snapshot 为 null。 */
    @Test
    fun `snapshot and reset clears samples`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 2)
        accumulator.record(MEASURE_NANOS, DRAW_NANOS, SYNC_NANOS, SWAP_NANOS, delayed = true)

        val first = accumulator.snapshotAndReset()
        assertEquals(MEASURE_NANOS, first?.measureLayoutNanos)
        assertEquals(1, first?.delayedFrames)
        assertNull(accumulator.snapshotAndReset())
    }

    /** reset 不经 snapshot 直接清空逻辑状态。 */
    @Test
    fun `reset clears logical state without snapshot`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 2)
        accumulator.record(MEASURE_NANOS, DRAW_NANOS, SYNC_NANOS, SWAP_NANOS, delayed = false)

        accumulator.reset()

        assertNull(accumulator.snapshotAndReset())
    }

    /** 未满容量的样本全部保留在滚动总计中。 */
    @Test
    fun `fewer samples than capacity are all retained`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 3)
        accumulator.record(1L, 2L, 3L, 4L, delayed = false)
        accumulator.record(10L, 20L, 30L, 40L, delayed = true)

        val snapshot = accumulator.snapshotAndReset()
        assertEquals(11L, snapshot?.measureLayoutNanos)
        assertEquals(22L, snapshot?.drawNanos)
        assertEquals(33L, snapshot?.syncNanos)
        assertEquals(44L, snapshot?.swapBuffersNanos)
        assertEquals(1, snapshot?.delayedFrames)
    }

    /** delayed 帧被滚动窗口淘汰时计数同步递减。 */
    @Test
    fun `delayed count decreases when delayed frame evicted`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 1)
        accumulator.record(MEASURE_NANOS, DRAW_NANOS, SYNC_NANOS, SWAP_NANOS, delayed = true)
        assertEquals(1, accumulator.snapshotAndReset()?.delayedFrames)

        // 容量 1：上一帧（delayed）被本帧（非 delayed）淘汰。
        accumulator.record(MEASURE_NANOS, DRAW_NANOS, SYNC_NANOS, SWAP_NANOS, delayed = false)
        assertEquals(0, accumulator.snapshotAndReset()?.delayedFrames)
    }

    /** 混合 delayed 帧时窗口内计数精确。 */
    @Test
    fun `delayed frames accumulate accurately within window`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 4)
        accumulator.record(1L, 1L, 1L, 1L, delayed = true)
        accumulator.record(1L, 1L, 1L, 1L, delayed = false)
        accumulator.record(1L, 1L, 1L, 1L, delayed = true)

        assertEquals(2, accumulator.snapshotAndReset()?.delayedFrames)
    }

    /** 非正容量在构造期立即拒绝。 */
    @Test
    fun `capacity must be positive`() {
        try {
            FrameMetricsWindowAccumulator(capacity = 0)
            fail("expected IllegalArgumentException for zero capacity")
        } catch (expected: IllegalArgumentException) {
            assertEquals("capacity must be positive", expected.message)
        }
    }

    companion object {
        /** 单帧 measure/layout 耗时（纳秒）。 */
        private const val MEASURE_NANOS = 1_000_000L

        /** 单帧 draw 耗时（纳秒）。 */
        private const val DRAW_NANOS = 2_000_000L

        /** 单帧 sync 耗时（纳秒）。 */
        private const val SYNC_NANOS = 500_000L

        /** 单帧 swapBuffers 耗时（纳秒）。 */
        private const val SWAP_NANOS = 3_000_000L
    }
}
