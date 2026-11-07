package com.apm.slowmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * StackSamplingProfiler 生命周期单元测试（Robolectric）。
 *
 * 覆盖确定性门控行为：采样开关关闭时不进入采样态、stopSampling 同步输出
 * Top N 结果与样本计数、destroy 释放采样线程。
 * 热点排序统计依赖主线程真实堆栈与采样线程调度，属 Instrumentation 范畴。
 */
@RunWith(RobolectricTestRunner::class)
class StackSamplingProfilerTest {

    /** 每个用例后释放采样线程，避免跨用例泄漏。 */
    @After
    fun tearDown() {
        profiler?.destroy()
        profiler = null
    }

    /** 当前用例持有的 profiler 实例。 */
    private var profiler: StackSamplingProfiler? = null

    /** 采样开关关闭时 startSampling 不进入采样态，stop 输出零样本。 */
    @Test
    fun `disabled sampling never captures samples`() {
        val sampler = StackSamplingProfiler(SlowMethodConfig(enableStackSampling = false))
        profiler = sampler

        var callbackCount = 0
        sampler.onSamplingComplete = { topMethods, sampleCount ->
            callbackCount++
            assertTrue(topMethods.isEmpty())
            assertEquals(0, sampleCount)
        }

        sampler.startSampling()
        sampler.stopSampling()

        assertEquals(1, callbackCount)
    }

    /** 启用采样时 stopSampling 同步回调 Top N 列表与样本计数。 */
    @Test
    fun `enabled sampling reports top methods on stop`() {
        val sampler = StackSamplingProfiler(
            SlowMethodConfig(
                enableStackSampling = true,
                samplingIntervalMs = SAMPLING_INTERVAL_MS,
                samplingWindowMs = SAMPLING_WINDOW_MS,
                topMethodCount = TOP_METHOD_COUNT,
            )
        )
        profiler = sampler

        var reportedSampleCount = -1
        sampler.onSamplingComplete = { _, sampleCount ->
            reportedSampleCount = sampleCount
        }

        sampler.startSampling()
        sampler.stopSampling()

        // 立即 stop：pending 的采样任务被移除，样本计数为非负值且不超过窗口理论采样数。
        assertTrue(reportedSampleCount >= 0)
        assertTrue(reportedSampleCount <= MAX_POSSIBLE_SAMPLES)
    }

    /** destroy 释放采样线程后线程退出。 */
    @Test
    fun `destroy quits sampling thread`() {
        val sampler = StackSamplingProfiler(
            SlowMethodConfig(enableStackSampling = false)
        )
        sampler.destroy()

        val threadField = StackSamplingProfiler::class.java.getDeclaredField("samplingThread")
        threadField.isAccessible = true
        val thread = threadField.get(sampler) as Thread
        thread.join(JOIN_TIMEOUT_MS)
        assertTrue("sampling thread should exit after destroy", !thread.isAlive)
    }

    companion object {
        /** 测试采样间隔（毫秒）。 */
        private const val SAMPLING_INTERVAL_MS = 50L

        /** 测试采样窗口（毫秒）。 */
        private const val SAMPLING_WINDOW_MS = 10_000L

        /** 测试 Top N 数量。 */
        private const val TOP_METHOD_COUNT = 5

        /** 立即 stop 场景下的最大可能样本数（远小于窗口理论值）。 */
        private const val MAX_POSSIBLE_SAMPLES = 3

        /** 等待采样线程退出的超时（毫秒）。 */
        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
