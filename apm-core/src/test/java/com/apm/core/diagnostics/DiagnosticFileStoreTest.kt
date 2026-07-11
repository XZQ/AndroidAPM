package com.apm.core.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real-filesystem tests for bounded diagnostic persistence and export.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticFileStoreTest {

    /** Temporary diagnostics directory owned by each test. */
    private lateinit var tempDir: File

    /** Creates an isolated directory. */
    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("apm-diagnostics-store").toFile()
    }

    /** Removes all test artifacts. */
    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Appended records must be readable in their persisted order. */
    @Test
    fun `append and read preserve entries`() {
        val store = fileStore()
        store.append(diagnosticEntry(sequence = 1L, message = "one"))
        store.append(diagnosticEntry(sequence = 2L, message = "two"))

        val read = store.readAll()

        assertEquals(listOf("one", "two"), read.entries.map(DiagnosticEntry::message))
        assertEquals(0L, read.corruptRecords)
    }

    /** Rotation must retain no more than the configured number of bounded segments. */
    @Test
    fun `append rotates and enforces retained segment count`() {
        val store = fileStore(maxFileBytes = MIN_TEST_FILE_BYTES, retainedFileCount = 2)
        repeat(400) { index ->
            store.append(diagnosticEntry(sequence = index.toLong(), message = "x".repeat(400)))
        }

        val segments = tempDir.listFiles { file -> file.name.endsWith(JSONL_SUFFIX) }.orEmpty()
        assertTrue(segments.size <= 2)
        assertTrue(store.retainedBytes() <= 2L * MIN_TEST_FILE_BYTES)
    }

    /** A truncated final record must not hide earlier readable records. */
    @Test
    fun `corrupt lines are skipped and counted`() {
        val store = fileStore()
        store.append(diagnosticEntry(sequence = 1L))
        File(tempDir, ACTIVE_FILE_NAME).appendText("{broken\n")

        val read = store.readAll()

        assertEquals(1, read.entries.size)
        assertEquals(1L, read.corruptRecords)
    }

    /** Export must contain a controlled manifest and merged JSONL journal. */
    @Test
    fun `export contains manifest and readable JSONL`() {
        val store = fileStore()
        store.append(diagnosticEntry(sequence = 1L))
        val target = File(tempDir, "diagnostics.zip")

        val result = store.exportTo(target, DiagnosticStatus.INACTIVE)

        assertTrue(result.success)
        assertEquals(1, result.exportedRecords)
        ZipFile(target).use { zip ->
            assertNotNull(zip.getEntry("manifest.json"))
            assertNotNull(zip.getEntry("diagnostics.jsonl"))
        }
    }

    /** Clear must remove every persisted JSONL segment. */
    @Test
    fun `clear removes all segments`() {
        val store = fileStore()
        store.append(diagnosticEntry(sequence = 1L))

        assertTrue(store.clear())
        assertTrue(tempDir.listFiles { file -> file.name.endsWith(JSONL_SUFFIX) }.orEmpty().isEmpty())
    }

    /** Builds a real store with test-specific file bounds. */
    private fun fileStore(
        maxFileBytes: Long = MIN_TEST_FILE_BYTES,
        retainedFileCount: Int = 3
    ): DiagnosticFileStore {
        return DiagnosticFileStore(
            directory = tempDir,
            config = DiagnosticsConfig(
                maxFileBytes = maxFileBytes,
                retainedFileCount = retainedFileCount
            )
        )
    }

    /** Creates a complete diagnostic entry for real codec and file testing. */
    private fun diagnosticEntry(
        sequence: Long,
        message: String = "message"
    ): DiagnosticEntry = DiagnosticEntry(
        sequence = sequence,
        timestampMs = sequence + 1_000L,
        sessionId = "session",
        level = DiagnosticLevel.ERROR,
        component = "storage",
        code = "write",
        message = message,
        processName = "com.example",
        threadName = "apm-diagnostics-writer",
        exceptionClass = null,
        exceptionMessage = null,
        stackTrace = null,
        stackHash = null
    )

    private companion object {
        /** Minimum segment size accepted by production validation. */
        private const val MIN_TEST_FILE_BYTES = 64L * 1024L
        /** Active journal file name from the public persistence contract. */
        private const val ACTIVE_FILE_NAME = "diagnostics.jsonl"
        /** Suffix used to identify diagnostic journal segments. */
        private const val JSONL_SUFFIX = ".jsonl"
    }
}
