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

    /** 异步写入应按 maxLinesPerFile 合批：10 条事件产生不超过 ceil(10/4)=3 个文件。 */
    @Test
    fun `async writes are batched into few ipc files`() {
        val dir = createTempDirectory(prefix = "apm-ipc-batch-test").toFile()
        try {
            val writer = ProcessEventCoordinator(
                ipcDir = dir,
                isUploaderProcess = false,
                maxLinesPerFile = BATCH_LINES_PER_FILE
            )
            writer.start()

            // 异步提交 10 条普通事件
            for (i in 0 until BATCH_EVENT_COUNT) {
                writer.writeEvent(ApmEvent(module = "ipc", name = "batched_$i"))
            }

            // 轮询等待写线程消化全部事件（已发布 + 仍在缓冲）
            val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val published = dir.listFilesByExtension(READY_EXTENSION)
                    .sumOf { file -> file.readLines().count { it.isNotBlank() } }
                if (published + writer.pendingBufferSize() == BATCH_EVENT_COUNT) {
                    break
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            // 全部事件就位后只做一次收尾 flush，避免把批次切碎
            writer.flushPendingNow()
            val totalLines = dir.listFilesByExtension(READY_EXTENSION)
                .sumOf { file -> file.readLines().count { it.isNotBlank() } }

            assertEquals(BATCH_EVENT_COUNT, totalLines)
            val fileCount = dir.listFilesByExtension(READY_EXTENSION).size
            val maxExpectedFiles =
                (BATCH_EVENT_COUNT + BATCH_LINES_PER_FILE - 1) / BATCH_LINES_PER_FILE
            assertTrue(
                "Expected <= $maxExpectedFiles batched files, got $fileCount",
                fileCount <= maxExpectedFiles
            )

            // 扫描端应能逐行消费整批事件
            val received = mutableListOf<ApmEvent>()
            val scanner = ProcessEventCoordinator(
                ipcDir = dir,
                isUploaderProcess = true,
                scanIntervalMs = LONG_SCAN_INTERVAL_MS
            )
            scanner.onRemoteEvent = { event -> received += event }
            scanner.start()
            scanner.scanAndConsumeNow()
            assertEquals(BATCH_EVENT_COUNT, received.size)

            writer.stop()
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

        /** 合批测试的事件总数。 */
        private const val BATCH_EVENT_COUNT = 10

        /** 合批测试的单文件行数上限。 */
        private const val BATCH_LINES_PER_FILE = 4

        /** 异步断言超时（毫秒）。 */
        private const val AWAIT_TIMEOUT_MS = 3_000L

        /** 异步断言轮询间隔（毫秒）。 */
        private const val POLL_INTERVAL_MS = 20L
    }
}
