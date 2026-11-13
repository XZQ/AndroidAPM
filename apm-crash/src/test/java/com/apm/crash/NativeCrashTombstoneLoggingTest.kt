package com.apm.crash

import com.apm.core.ApmLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/**
 * tombstone 扫描可观测性测试。
 *
 * 生产设备受 SELinux 约束读不到 `/data/tombstones/`；此时降级扫描必须通过
 * 模块 logger 留下一次性证据，而不是静默跳过。扫描间隔内的重复调用受 60 秒限频保护。
 *
 * 注意：[NativeCrashMonitor] 是 object，限频状态跨用例共享；本套件保持单用例以固定调用顺序。
 */
class NativeCrashTombstoneLoggingTest {

    /** 记录警告消息的测试 logger。 */
    private class RecordingLogger : ApmLogger {
        val warnings = mutableListOf<String>()
        override fun d(message: String) = Unit
        override fun w(message: String) {
            warnings.add(message)
        }
        override fun e(message: String, throwable: Throwable?) = Unit
    }

    /**
     * 目录不可读时写入一条警告；间隔内的后续调用（含公开无参入口）被限频且不重复记录。
     * 若宿主机真实存在 `/data/tombstones/`（如 root 环境），跳过该用例而不是伪造结论。
     */
    @Test
    fun checkRecentTombstone_unreadableDirectory_logsOneWarningAndRateLimits() {
        assumeFalse(File("/data/tombstones").exists())
        val logger = RecordingLogger()

        NativeCrashMonitor.checkRecentTombstone(logger)
        assertEquals(1, logger.warnings.size)
        assertTrue(
            "Warning should mention unreadability, was: ${logger.warnings[0]}",
            logger.warnings[0].contains("not readable")
        )

        // 公开无参入口保持历史签名；60 秒扫描间隔内被限频，安全返回且不新增警告
        NativeCrashMonitor.checkRecentTombstone()
        NativeCrashMonitor.checkRecentTombstone(logger)
        assertEquals(1, logger.warnings.size)
    }
}
