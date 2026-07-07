package com.apm.battery

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CpuJiffiesSampler] 使用率计算测试。
 * 通过注入 jiffies 读取器、时钟与 clockTickHz 验证不同内核频率下的换算正确性。
 */
class CpuJiffiesSamplerTest {

    /**
     * 构造可控采样器。
     *
     * @param clockTickHz 注入的时钟频率
     * @param jiffies 可变 jiffies 源
     * @param nowMs 可变墙钟源
     * @param threshold 高 CPU 阈值（单核分数）
     * @return 采样器与回调结果引用
     */
    private fun buildSampler(
        clockTickHz: Long,
        jiffies: AtomicLong,
        nowMs: AtomicLong,
        threshold: Float
    ): Pair<CpuJiffiesSampler, AtomicLong> {
        val reportedPercent = AtomicLong(NO_REPORT)
        val sampler = CpuJiffiesSampler(
            config = BatteryConfig(
                cpuThresholdPercent = threshold,
                cpuSustainedSeconds = 0L
            ),
            clockTickHz = clockTickHz,
            jiffiesReader = { jiffies.get() },
            clock = { nowMs.get() }
        )
        sampler.onCpuHigh = { percent, _ ->
            // 用千分位整数保存 float，规避原子 float 缺失
            reportedPercent.set((percent * PERCENT_SCALE).toLong())
        }
        return sampler to reportedPercent
    }

    /** 100 Hz：1 秒内 50 jiffies = 0.5 单核占用。 */
    @Test
    fun `usage math is correct at 100hz`() {
        val jiffies = AtomicLong(0L)
        val now = AtomicLong(0L)
        val (sampler, reported) = buildSampler(
            clockTickHz = 100L, jiffies = jiffies, nowMs = now, threshold = 0.4f
        )
        sampler.start()

        // 1 秒后消耗 50 jiffies（= 500ms CPU 时间 → 0.5 核）
        jiffies.set(50L)
        now.set(1_000L)
        sampler.sample()

        assertEquals(500L, reported.get())
    }

    /** 250 Hz：同样 0.5 核占用需要 125 jiffies —— 验证频率参与换算。 */
    @Test
    fun `usage math respects clock tick rate`() {
        val jiffies = AtomicLong(0L)
        val now = AtomicLong(0L)
        val (sampler, reported) = buildSampler(
            clockTickHz = 250L, jiffies = jiffies, nowMs = now, threshold = 0.4f
        )
        sampler.start()

        // 250 Hz 下 125 jiffies = 500ms CPU 时间 → 0.5 核
        jiffies.set(125L)
        now.set(1_000L)
        sampler.sample()

        assertEquals(500L, reported.get())
    }

    /** 低于阈值不触发回调。 */
    @Test
    fun `below threshold does not report`() {
        val jiffies = AtomicLong(0L)
        val now = AtomicLong(0L)
        val (sampler, reported) = buildSampler(
            clockTickHz = 100L, jiffies = jiffies, nowMs = now, threshold = 0.9f
        )
        sampler.start()

        jiffies.set(50L)
        now.set(1_000L)
        sampler.sample()

        assertEquals(NO_REPORT, reported.get())
    }

    companion object {
        /** 未上报标记。 */
        private const val NO_REPORT = -1L

        /** float → 千分位整数的缩放因子。 */
        private const val PERCENT_SCALE = 1000f
    }
}
