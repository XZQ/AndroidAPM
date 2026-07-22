package com.apm.core

import com.apm.model.ApmEvent
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.model.ApmPriority
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

    /** Pending retention is bounded before any writer task can accumulate. */
    @Test
    fun `pending byte budget rejects oversized retained event`() {
        val dir = createTempDirectory(prefix = "apm-ipc-pending-budget").toFile()
        try {
            val drops = mutableListOf<Pair<ApmPriority, SdkDropReason>>()
            val coordinator = ProcessEventCoordinator(
                ipcDir = dir,
                isUploaderProcess = false,
                maxPendingBytes = 1L
            )
            coordinator.onDrop = { priority, reason -> drops += priority to reason }
            coordinator.start()

            coordinator.writeEvent(
                ApmEvent(module = "ipc", name = "pending", priority = ApmPriority.HIGH)
            )

            assertEquals(0, coordinator.pendingBufferSize())
            assertEquals(0L, coordinator.pendingBufferBytes())
            assertEquals(
                listOf(ApmPriority.HIGH to SdkDropReason.IPC_PENDING_BYTE_BUDGET),
                drops
            )
            coordinator.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** One encoded line larger than the atomic file budget is rejected without a temp residue. */
    @Test
    fun `ipc file byte budget rejects oversized encoded event`() {
        val dir = createTempDirectory(prefix = "apm-ipc-file-budget").toFile()
        try {
            val coordinator = ProcessEventCoordinator(
                ipcDir = dir,
                isUploaderProcess = false,
                maxEventPayloadBytes = 1024 * 1024,
                maxFileBytes = 64L,
                maxDirectoryBytes = 1024L
            )
            coordinator.start()

            val result = coordinator.writeEventSyncWithResult(
                ApmEvent(module = "ipc", name = "file-budget", fields = mapOf("value" to "x".repeat(256)))
            )

            assertFalse(result.success)
            assertEquals(SdkDropReason.IPC_FILE_BYTE_BUDGET, result.dropReason)
            assertEquals(0, dir.listFilesByExtension(READY_EXTENSION).size)
            assertEquals(0, dir.listFilesByExtension(TEMP_EXTENSION).size)
            coordinator.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A process-shared directory lock prevents a second file from exceeding the byte budget. */
    @Test
    fun `ipc directory byte budget rejects new ready file`() {
        val dir = createTempDirectory(prefix = "apm-ipc-directory-budget").toFile()
        val event = ApmEvent(module = "ipc", name = "directory-budget")
        try {
            val firstWriter = ProcessEventCoordinator(dir, isUploaderProcess = false)
            firstWriter.start()
            assertTrue(firstWriter.writeEventSync(event))
            firstWriter.stop()
            val existingFileBytes = dir.listFilesByExtension(READY_EXTENSION).single().length()

            val secondWriter = ProcessEventCoordinator(
                ipcDir = dir,
                isUploaderProcess = false,
                maxFileBytes = existingFileBytes,
                maxDirectoryBytes = existingFileBytes
            )
            secondWriter.start()
            val result = secondWriter.writeEventSyncWithResult(event)

            assertFalse(result.success)
            assertEquals(SdkDropReason.IPC_DIRECTORY_BYTE_BUDGET, result.dropReason)
            assertEquals(1, dir.listFilesByExtension(READY_EXTENSION).size)
            assertEquals(0, dir.listFilesByExtension(TEMP_EXTENSION).size)
            secondWriter.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Consent revocation removes ready hand-off files instead of flushing or consuming them. */
    @Test
    fun `consent revocation clears ipc artifacts`() {
        val dir = createTempDirectory(prefix = "apm-ipc-consent-test").toFile()
        try {
            val coordinator = ProcessEventCoordinator(dir, isUploaderProcess = false)
            coordinator.start()
            assertTrue(coordinator.writeEventSync(ApmEvent(module = "ipc", name = "private")))

            val result = coordinator.stopAndClearForConsentRevocation()

            assertEquals(1, result.clearedFileCount)
            assertTrue(result.allFilesCleared)
            assertEquals(0, dir.listFilesByExtension(READY_EXTENSION).size)
            assertEquals(0, dir.listFilesByExtension(TEMP_EXTENSION).size)
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
