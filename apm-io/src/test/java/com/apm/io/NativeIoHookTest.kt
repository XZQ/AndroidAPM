package com.apm.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NativeIoHook 行为测试。
 *
 * 覆盖 Java wrapper 的字节/操作计数、Native 事件吞吐归属、
 * 路径维度统计与容量上限、以及 init/destroy 生命周期语义。
 * 监视线程与 Native PLT Hook 在测试配置中关闭，保证确定性；
 * Apm.emit 在未初始化时是安全 no-op，事件门控由 IoConfigTest 的纯 gate 测试承担。
 */
class NativeIoHookTest {

    /** 构造关闭所有后台监视线程与 Native hook 的可运行配置。 */
    private fun quietConfig(
        enableThroughput: Boolean = true,
        enableZeroCopy: Boolean = false
    ): IoConfig = IoConfig(
        enableNativePltHook = false,
        enableCloseableLeak = false,
        enableFdLeakDetection = false,
        enableThroughputStats = enableThroughput,
        enableZeroCopyDetection = enableZeroCopy
    )

    /** 单字节 read() 计逻辑字节与操作数，EOF 读取不计数。 */
    @Test
    fun `byte reads count logical bytes and operations`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // 两个有效字节 + 一次 EOF，EOF 不进入吞吐量统计
            val wrapped = hook.wrapInputStream(ByteArrayInputStream(byteArrayOf(1, 2)), TEST_PATH)
            assertEquals(1, wrapped.read())
            assertEquals(2, wrapped.read())
            assertEquals(-1, wrapped.read())
            val stats = hook.getGlobalStats()
            assertEquals(2L, stats[KEY_TOTAL_READ_BYTES])
            assertEquals(0L, stats[KEY_TOTAL_WRITE_BYTES])
            assertEquals(2L, stats[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** 缓冲 read() 只按实际转移字节数计数。 */
    @Test
    fun `buffer reads count actual transferred bytes`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // 10 字节源一次读完，EOF 次不计数
            val wrapped = hook.wrapInputStream(ByteArrayInputStream(ByteArray(SOURCE_BYTES)), TEST_PATH)
            assertEquals(SOURCE_BYTES, wrapped.read(ByteArray(SOURCE_BYTES + SPARE_CAPACITY), 0, SOURCE_BYTES + SPARE_CAPACITY))
            assertEquals(-1, wrapped.read(ByteArray(SOURCE_BYTES), 0, SOURCE_BYTES))
            assertEquals(SOURCE_BYTES.toLong(), hook.getGlobalStats()[KEY_TOTAL_READ_BYTES])
            assertEquals(1L, hook.getGlobalStats()[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** write 计字节与操作数；flush 与幂等 close 不进入吞吐量。 */
    @Test
    fun `writes count bytes while flush and repeated close stay uncounted`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            val sink = ByteArrayOutputStream()
            val wrapped = hook.wrapOutputStream(sink, TEST_PATH)
            // 1 字节单写 + 9 字节范围写 = 10 字节、2 次操作
            wrapped.write(SINGLE_BYTE)
            wrapped.write(ByteArray(BULK_BYTES), 0, BULK_BYTES)
            // flush 只上报延迟，不计数
            wrapped.flush()
            // 重复 close 幂等，不重复计数
            wrapped.close()
            wrapped.close()
            assertEquals(EXPECTED_WRITE_BYTES.toInt(), sink.size())
            assertEquals(EXPECTED_WRITE_BYTES, hook.getGlobalStats()[KEY_TOTAL_WRITE_BYTES])
            assertEquals(2L, hook.getGlobalStats()[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** 关闭吞吐量配置后所有 wrapper 读写字节均不计数。 */
    @Test
    fun `disabled throughput configuration skips accounting`() {
        val hook = NativeIoHook(quietConfig(enableThroughput = false))
        hook.init()
        try {
            val wrapped = hook.wrapInputStream(ByteArrayInputStream(ByteArray(SOURCE_BYTES)), TEST_PATH)
            assertEquals(SOURCE_BYTES, wrapped.read(ByteArray(SOURCE_BYTES), 0, SOURCE_BYTES))
            // 未启用吞吐量统计时全局计数保持为零
            val stats = hook.getGlobalStats()
            assertEquals(0L, stats[KEY_TOTAL_READ_BYTES])
            assertEquals(0L, stats[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** 未 init 的 hook 直接忽略所有读写回调。 */
    @Test
    fun `callbacks before init are ignored`() {
        val hook = NativeIoHook(quietConfig())
        try {
            // initialized=false 时回调早退
            hook.onRead(TEST_PATH, SOURCE_BYTES, LARGE_BUFFER)
            hook.onWrite(TEST_PATH, SOURCE_BYTES, LARGE_BUFFER)
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** 非正数字节数不会进入吞吐量统计。 */
    @Test
    fun `non positive transfers are ignored`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // bytesRead/bytesWritten <= 0 属于 EOF 或无效回调，直接忽略
            hook.onRead(TEST_PATH, 0, LARGE_BUFFER)
            hook.onWrite(TEST_PATH, NEGATIVE_BYTES, LARGE_BUFFER)
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_READ_BYTES])
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_WRITE_BYTES])
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** Native read/write 事件分别累计读写字节与总操作数。 */
    @Test
    fun `native events split read and write accounting`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // Native 回调路径与 Java wrapper 共用同一套全局计数
            hook.handleNativeIoEvent(OP_READ, TEST_PATH, NATIVE_READ_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            hook.handleNativeIoEvent(OP_WRITE, TEST_PATH, NATIVE_WRITE_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            val stats = hook.getGlobalStats()
            assertEquals(NATIVE_READ_BYTES, stats[KEY_TOTAL_READ_BYTES])
            assertEquals(NATIVE_WRITE_BYTES, stats[KEY_TOTAL_WRITE_BYTES])
            assertEquals(2L, stats[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** Native open/close 事件不是吞吐量操作。 */
    @Test
    fun `native open and close operations are not throughput ops`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // 只有 read/write 承载真实 syscall 数据量；open/close 只做生命周期信号
            hook.handleNativeIoEvent(OP_OPEN, TEST_PATH, NATIVE_READ_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            hook.handleNativeIoEvent(OP_CLOSE, TEST_PATH, NATIVE_READ_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** 零字节 Native 事件不进入吞吐量统计。 */
    @Test
    fun `native zero byte transfers are not counted`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // bytes <= 0 的回调没有可聚合的数据量
            hook.handleNativeIoEvent(OP_READ, TEST_PATH, ZERO_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_READ_BYTES])
            assertEquals(0L, hook.getGlobalStats()[KEY_TOTAL_IO_OPS])
        } finally {
            hook.destroy()
        }
    }

    /** 路径维度统计正确拆分读写字节与操作数。 */
    @Test
    fun `per path statistics split read write and op counts`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            hook.handleNativeIoEvent(OP_READ, TEST_PATH, NATIVE_READ_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            hook.handleNativeIoEvent(OP_WRITE, TEST_PATH, NATIVE_WRITE_BYTES, ZERO_DURATION_MS, NOT_MAIN_THREAD)
            val stats = hook.getThroughputStats().single()
            assertEquals(TEST_PATH, stats.path)
            assertEquals(NATIVE_READ_BYTES, stats.readBytes.get())
            assertEquals(NATIVE_WRITE_BYTES, stats.writeBytes.get())
            assertEquals(2L, stats.opCount.get())
        } finally {
            hook.destroy()
        }
    }

    /** 返回的吞吐量快照是防御性拷贝，外部修改不影响内部状态。 */
    @Test
    fun `throughput snapshots are defensive copies`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            hook.onRead(TEST_PATH, SOURCE_BYTES, LARGE_BUFFER)
            // 对返回快照的修改不得写回内部统计
            val snapshot = hook.getThroughputStats().single()
            snapshot.readBytes.addAndGet(POISON_INCREMENT)
            assertEquals(SOURCE_BYTES.toLong(), hook.getThroughputStats().single().readBytes.get())
        } finally {
            hook.destroy()
        }
    }

    /** 高基数路径被限界到固定容量并归入溢出桶。 */
    @Test
    fun `path cardinality is bounded with an overflow bucket`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // 300 个不同路径超过 256 上限，多出的全部计入 "<other>"
            repeat(OVERFLOW_PATHS) { index -> hook.onRead("overflow-$index", SINGLE_BYTE, LARGE_BUFFER) }
            val stats = hook.getThroughputStats()
            assertEquals(MAX_TRACKED_PATHS, stats.size)
            assertTrue(stats.any { it.path == OVERFLOW_BUCKET_NAME })
        } finally {
            hook.destroy()
        }
    }

    /** 关闭未知来源的回调是 no-op，不影响任何统计。 */
    @Test
    fun `unknown source close is a no op`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        try {
            // 未注册会话的 close 回调直接早退
            hook.onClose(Any(), ZERO_BYTES)
            assertTrue(hook.getThroughputStats().isEmpty())
        } finally {
            hook.destroy()
        }
    }

    /** destroy 清空路径统计并终止后续计数。 */
    @Test
    fun `destroy clears path statistics and ends accounting`() {
        val hook = NativeIoHook(quietConfig())
        hook.init()
        hook.onRead(TEST_PATH, SOURCE_BYTES, LARGE_BUFFER)
        // destroy 结束生命周期：路径统计清空、后续回调被忽略
        hook.destroy()
        assertTrue(hook.getThroughputStats().isEmpty())
        hook.onRead(TEST_PATH, SOURCE_BYTES, LARGE_BUFFER)
        assertTrue(hook.getThroughputStats().isEmpty())
    }

    /** 零拷贝检测在链条饱和时保持有界且不产生崩溃或吞吐量污染。 */
    @Test
    fun `buffer copy saturates without callbacks or throughput pollution`() {
        val hook = NativeIoHook(quietConfig(enableZeroCopy = true))
        hook.init()
        try {
            // 超过拷贝链上限的记录被静默丢弃；零拷贝路径不触碰吞吐量统计
            repeat(OVERFLOW_PATHS) { index ->
                hook.onBufferCopy("copy-$index", "target-$index", NATIVE_READ_BYTES, SINGLE_BYTE)
            }
            assertTrue(hook.getThroughputStats().isEmpty())
        } finally {
            hook.destroy()
        }
    }

    companion object {
        /** 测试路径。 */
        private const val TEST_PATH = "test-path"

        /** 全局统计 key：总读取字节。 */
        private const val KEY_TOTAL_READ_BYTES = "totalReadBytes"

        /** 全局统计 key：总写入字节。 */
        private const val KEY_TOTAL_WRITE_BYTES = "totalWriteBytes"

        /** 全局统计 key：总 IO 操作数。 */
        private const val KEY_TOTAL_IO_OPS = "totalIoOps"

        /** 源数据字节数。 */
        private const val SOURCE_BYTES = 10

        /** 缓冲区冗余容量。 */
        private const val SPARE_CAPACITY = 6

        /** 单字节写入值。 */
        private const val SINGLE_BYTE = 0x41

        /** 批量写入字节数。 */
        private const val BULK_BYTES = 9

        /** 期望写入总字节。 */
        private const val EXPECTED_WRITE_BYTES = 10L

        /** 避免触发小 buffer 分支的缓冲大小。 */
        private const val LARGE_BUFFER = 8_192

        /** 负数字节数。 */
        private const val NEGATIVE_BYTES = -1

        /** Native 读事件字节数。 */
        private const val NATIVE_READ_BYTES = 5L

        /** Native 写事件字节数。 */
        private const val NATIVE_WRITE_BYTES = 3L

        /** 零耗时。 */
        private const val ZERO_DURATION_MS = 0L

        /** 零字节。 */
        private const val ZERO_BYTES = 0L

        /** 非主线程标记。 */
        private const val NOT_MAIN_THREAD = false

        /** Native open 操作名。 */
        private const val OP_READ = "read"

        /** Native write 操作名。 */
        private const val OP_WRITE = "write"

        /** Native open 操作名。 */
        private const val OP_OPEN = "open"

        /** Native close 操作名。 */
        private const val OP_CLOSE = "close"

        /** 用于验证快照隔离的污染增量。 */
        private const val POISON_INCREMENT = 999L

        /** 超过路径上限的路径数量。 */
        private const val OVERFLOW_PATHS = 300

        /** 路径统计容量上限，与 NativeIoHook.MAX_TRACKED_PATHS 一致。 */
        private const val MAX_TRACKED_PATHS = 256

        /** 高基数溢出桶名称。 */
        private const val OVERFLOW_BUCKET_NAME = "<other>"
    }
}
