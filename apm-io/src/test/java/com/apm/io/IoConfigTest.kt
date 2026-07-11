package com.apm.io

import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * IoConfig 默认值测试。
 */
class IoConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableIoMonitor is true`() {
        val config = IoConfig()
        assertTrue(config.enableIoMonitor)
    }

    /** 默认主线程 IO 阈值 50ms。 */
    @Test
    fun `default mainThreadIoThresholdMs is 50`() {
        val config = IoConfig()
        assertEquals(50L, config.mainThreadIoThresholdMs)
    }

    /** 默认单次 IO 阈值 500ms。 */
    @Test
    fun `default singleIoThresholdMs is 500`() {
        val config = IoConfig()
        assertEquals(500L, config.singleIoThresholdMs)
    }

    /** 默认大 buffer 阈值 512KB。 */
    @Test
    fun `default largeBufferSize is 512KB`() {
        val config = IoConfig()
        assertEquals(512 * 1024L, config.largeBufferSize)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = IoConfig(mainThreadIoThresholdMs = 100L, singleIoThresholdMs = 1000L)
        assertEquals(100L, config.mainThreadIoThresholdMs)
        assertEquals(1000L, config.singleIoThresholdMs)
    }

    /** 吞吐窗口只在每个完整操作窗口边界触发。 */
    @Test
    fun `throughput gate reports at configured boundaries`() {
        assertFalse(ThroughputWindowGate.shouldReport(operationCount = 99L, configuredWindow = 100))
        assertTrue(ThroughputWindowGate.shouldReport(operationCount = 100L, configuredWindow = 100))
        assertTrue(ThroughputWindowGate.shouldReport(operationCount = 1L, configuredWindow = 0))
    }

    /** Native and Java proxy paths never count the same syscall twice. */
    @Test
    fun `throughput source policy selects wrapper or native by active call path`() {
        assertTrue(ThroughputSourcePolicy.shouldCountJava(enableThroughput = true))
        assertFalse(ThroughputSourcePolicy.shouldCountJava(enableThroughput = false))
        assertTrue(ThroughputSourcePolicy.shouldCountNative(enableThroughput = true, insideJavaProxy = false))
        assertFalse(ThroughputSourcePolicy.shouldCountNative(enableThroughput = true, insideJavaProxy = true))
    }

    /** Duplicate reads emit once when the configured threshold is first reached. */
    @Test
    fun `duplicate read gate emits only at threshold`() {
        assertFalse(DuplicateReadGate.shouldReport(readCount = 4, threshold = 5))
        assertTrue(DuplicateReadGate.shouldReport(readCount = 5, threshold = 5))
        assertFalse(DuplicateReadGate.shouldReport(readCount = 6, threshold = 5))
    }

    /** Small-buffer findings emit once per bounded path state. */
    @Test
    fun `small buffer gate suppresses repeated operations`() {
        assertTrue(SmallBufferGate.shouldReport(bufferSize = 1, threshold = 4_096, alreadyReported = false))
        assertFalse(SmallBufferGate.shouldReport(bufferSize = 1, threshold = 4_096, alreadyReported = true))
        assertFalse(SmallBufferGate.shouldReport(bufferSize = 8_192, threshold = 4_096, alreadyReported = false))
    }

    /** A synchronous Native callback nested inside a wrapper read is represented exactly once. */
    @Test
    fun `wrapper suppresses nested native throughput without losing logical bytes`() {
        val hook = NativeIoHook(
            IoConfig(
                enableNativePltHook = false,
                enableCloseableLeak = false,
                enableFdLeakDetection = false,
                enableThroughputStats = true
            )
        )
        hook.init()
        var emitted = false
        val source = object : InputStream() {
            /** Simulates a Native syscall callback on the same thread as the wrapper. */
            override fun read(): Int {
                if (emitted) return -1
                emitted = true
                hook.handleNativeIoEvent("read", "sample", 1L, 1L, false)
                return 1
            }
        }

        try {
            val wrapped = hook.wrapInputStream(source, "sample")
            assertEquals(1, wrapped.read())
            assertEquals(1L, hook.getGlobalStats()["totalIoOps"])
            assertEquals(1L, hook.getGlobalStats()["totalReadBytes"])
        } finally {
            hook.destroy()
        }
    }
}
