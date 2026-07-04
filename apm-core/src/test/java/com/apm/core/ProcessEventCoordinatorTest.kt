package com.apm.core

import com.apm.model.ApmEvent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * ProcessEventCoordinator 和 ProcessSessionId 单元测试。
 * 仅测试纯逻辑部分，不依赖 Android Context。
 */
class ProcessEventCoordinatorTest {

    /** ProcessSessionId 应返回非空字符串。 */
    @Test
    fun `session id is non-empty string`() {
        val sessionId = ProcessSessionId.get()
        assertTrue("Session ID should be non-empty", sessionId.isNotEmpty())
    }

    /** ProcessSessionId 多次调用应返回相同的值。 */
    @Test
    fun `session id is stable across calls`() {
        val id1 = ProcessSessionId.get()
        val id2 = ProcessSessionId.get()
        assertEquals("Session ID should be stable", id1, id2)
    }

    /** SessionId 应包含递增序号前缀。 */
    @Test
    fun `session id contains sequence prefix`() {
        val sessionId = ProcessSessionId.get()
        // 格式为 {seq}_{uuid_prefix}，下划线前是数字
        val underscoreIdx = sessionId.indexOf('_')
        assertTrue("Should contain underscore separator", underscoreIdx > 0)
        val prefix = sessionId.substring(0, underscoreIdx)
        assertTrue("Prefix should be numeric", prefix.toLongOrNull() != null)
    }

    /** 同步 IPC 写入应只发布完整的 .ipc 文件，不暴露临时文件给扫描端。 */
    @Test
    fun `sync write atomically publishes ready ipc file`() {
        val dir = createTempDirectory(prefix = "apm-ipc-test").toFile()
        try {
            val coordinator = ProcessEventCoordinator(dir, isUploaderProcess = false)
            coordinator.start()

            assertTrue(coordinator.writeEventSync(ApmEvent(module = "ipc", name = "critical")))

            val readyFiles = dir.listFilesByExtension(READY_EXTENSION)
            val tempFiles = dir.listFilesByExtension(TEMP_EXTENSION)
            assertEquals(1, readyFiles.size)
            assertEquals(0, tempFiles.size)
            coordinator.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 上传进程扫描应消费已发布事件并删除 ready 文件。 */
    @Test
    fun `scanner consumes published ipc file and deletes it`() {
        val dir = createTempDirectory(prefix = "apm-ipc-consume-test").toFile()
        try {
            val writer = ProcessEventCoordinator(dir, isUploaderProcess = false)
            writer.start()
            assertTrue(writer.writeEventSync(ApmEvent(module = "ipc", name = "remote_metric")))
            writer.stop()

            val received = mutableListOf<ApmEvent>()
            val scanner = ProcessEventCoordinator(
                ipcDir = dir,
                isUploaderProcess = true,
                scanIntervalMs = LONG_SCAN_INTERVAL_MS
            )
            scanner.onRemoteEvent = { event -> received += event }
            scanner.start()
            scanner.scanAndConsumeNow()

            assertEquals(1, received.size)
            assertEquals("remote_metric", received.single().name)
            assertEquals("remote_process", received.single().extras["ipc_source"])
            assertEquals(0, dir.listFilesByExtension(READY_EXTENSION).size)
            scanner.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Lists files by extension.
     *
     * @param extension expected file extension
     * @return matching files or an empty array
     */
    private fun File.listFilesByExtension(extension: String): Array<File> {
        return listFiles { file -> file.name.endsWith(extension) } ?: emptyArray()
    }

    companion object {
        /** Ready IPC file extension. */
        private const val READY_EXTENSION = ".ipc"

        /** Temporary IPC file extension. */
        private const val TEMP_EXTENSION = ".tmp"

        /** Long scan interval so tests can trigger scanning explicitly. */
        private const val LONG_SCAN_INTERVAL_MS = 60_000L
    }
}
