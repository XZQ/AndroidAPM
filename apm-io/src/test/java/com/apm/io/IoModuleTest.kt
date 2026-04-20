package com.apm.io

import org.junit.Assert.*
import org.junit.Test

/**
 * IoModule 配置和参数测试。
 *
 * 注：IoModule 的 onIoOperation 方法依赖 Looper.myLooper() 和 Looper.getMainLooper()
 * 判断当前线程是否为主线程，纯 JUnit 环境无主线程 Looper，无法测试核心 IO 检测逻辑。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 * 此文件验证 Config 层默认值和自定义值覆盖。
 */
class IoModuleTest {

    /** 默认配置开启 IO 监控。 */
    @Test
    fun `default config enables io monitor`() {
        val config = IoConfig()
        assertTrue(config.enableIoMonitor)
    }

    /** 默认主线程 IO 阈值 50ms。 */
    @Test
    fun `default main thread io threshold is 50ms`() {
        val config = IoConfig()
        assertEquals(50L, config.mainThreadIoThresholdMs)
    }

    /** 默认单次 IO 阈值 500ms。 */
    @Test
    fun `default single io threshold is 500ms`() {
        val config = IoConfig()
        assertEquals(500L, config.singleIoThresholdMs)
    }

    /** 默认大 buffer 阈值 512KB。 */
    @Test
    fun `default large buffer size is 512KB`() {
        val config = IoConfig()
        assertEquals(512 * 1_024L, config.largeBufferSize)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = IoConfig()
        assertEquals(4_000, config.maxStackTraceLength)
    }

    /** 默认开启自动 Hook。 */
    @Test
    fun `default auto hook is enabled`() {
        val config = IoConfig()
        assertTrue(config.enableAutoHook)
    }

    /** 默认小 buffer 阈值 4KB。 */
    @Test
    fun `default small buffer threshold is 4KB`() {
        val config = IoConfig()
        assertEquals(4_096, config.smallBufferThreshold)
    }

    /** 默认重复读阈值 5 次。 */
    @Test
    fun `default duplicate read threshold is 5`() {
        val config = IoConfig()
        assertEquals(5, config.duplicateReadThreshold)
    }

    /** 默认开启 Closeable 泄漏检测。 */
    @Test
    fun `default closeable leak is enabled`() {
        val config = IoConfig()
        assertTrue(config.enableCloseableLeak)
    }

    /** 默认开启 FD 泄漏检测。 */
    @Test
    fun `default fd leak detection is enabled`() {
        val config = IoConfig()
        assertTrue(config.enableFdLeakDetection)
    }

    /** 默认 FD 泄漏阈值 500。 */
    @Test
    fun `default fd leak threshold is 500`() {
        val config = IoConfig()
        assertEquals(500, config.fdLeakThreshold)
    }

    /** 默认开启吞吐量统计。 */
    @Test
    fun `default throughput stats is enabled`() {
        val config = IoConfig()
        assertTrue(config.enableThroughputStats)
    }

    /** 默认吞吐量统计窗口 100。 */
    @Test
    fun `default throughput window is 100`() {
        val config = IoConfig()
        assertEquals(100, config.throughputWindow)
    }

    /** 默认开启 Native PLT Hook。 */
    @Test
    fun `default native plt hook is enabled`() {
        val config = IoConfig()
        assertTrue(config.enableNativePltHook)
    }

    /** 默认关闭零拷贝检测。 */
    @Test
    fun `default zero copy detection is disabled`() {
        val config = IoConfig()
        assertFalse(config.enableZeroCopyDetection)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = IoConfig(
            enableIoMonitor = false,
            mainThreadIoThresholdMs = 100L,
            singleIoThresholdMs = 1_000L,
            largeBufferSize = 1_024 * 1_024L,
            maxStackTraceLength = 2_000,
            enableAutoHook = false,
            smallBufferThreshold = 8_192,
            duplicateReadThreshold = 10,
            enableCloseableLeak = false,
            enableFdLeakDetection = false,
            fdLeakThreshold = 1_000,
            enableThroughputStats = false,
            throughputWindow = 200,
            enableNativePltHook = false,
            enableZeroCopyDetection = true
        )
        // 验证所有自定义值已生效
        assertFalse(config.enableIoMonitor)
        assertEquals(100L, config.mainThreadIoThresholdMs)
        assertEquals(1_000L, config.singleIoThresholdMs)
        assertEquals(1_024 * 1_024L, config.largeBufferSize)
        assertEquals(2_000, config.maxStackTraceLength)
        assertFalse(config.enableAutoHook)
        assertEquals(8_192, config.smallBufferThreshold)
        assertEquals(10, config.duplicateReadThreshold)
        assertFalse(config.enableCloseableLeak)
        assertFalse(config.enableFdLeakDetection)
        assertEquals(1_000, config.fdLeakThreshold)
        assertFalse(config.enableThroughputStats)
        assertEquals(200, config.throughputWindow)
        assertFalse(config.enableNativePltHook)
        assertTrue(config.enableZeroCopyDetection)
    }

    /** 单次 IO 阈值大于主线程 IO 阈值。 */
    @Test
    fun `single io threshold is greater than main thread io threshold`() {
        val config = IoConfig()
        // 通用 IO 阈值应大于主线程专用阈值（主线程要求更严格）
        assertTrue(config.singleIoThresholdMs > config.mainThreadIoThresholdMs)
    }

    /** 大 buffer 阈值大于小 buffer 阈值。 */
    @Test
    fun `large buffer size is greater than small buffer threshold`() {
        val config = IoConfig()
        // 大 buffer 阈值（字节）应远大于小 buffer 阈值（字节）
        assertTrue(config.largeBufferSize > config.smallBufferThreshold)
    }

    /** FD 泄漏阈值为正数。 */
    @Test
    fun `fd leak threshold is positive`() {
        val config = IoConfig()
        // FD 泄漏阈值应为正整数
        assertTrue(config.fdLeakThreshold > 0)
    }

    /** 重复读阈值大于 0。 */
    @Test
    fun `duplicate read threshold is positive`() {
        val config = IoConfig()
        // 重复读阈值应为正整数
        assertTrue(config.duplicateReadThreshold > 0)
    }

    /** 吞吐量统计窗口为正数。 */
    @Test
    fun `throughput window is positive`() {
        val config = IoConfig()
        // 吞吐量统计窗口应为正整数
        assertTrue(config.throughputWindow > 0)
    }
}
