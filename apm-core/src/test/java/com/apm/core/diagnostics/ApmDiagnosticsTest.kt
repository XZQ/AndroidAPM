package com.apm.core.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Public facade tests using the real rolling file store.
 */
@RunWith(RobolectricTestRunner::class)
class ApmDiagnosticsTest {

    /** Isolated app-private-style directory. */
    private lateinit var tempDir: File

    /** Creates an isolated diagnostics directory. */
    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("apm-diagnostics-facade").toFile()
    }

    /** Stops the facade writer and removes test files. */
    @After
    fun tearDown() {
        ApmDiagnostics.clear()
        ApmDiagnostics.shutdown()
        tempDir.deleteRecursively()
    }

    /** Disabled initialization must expose safe inactive results. */
    @Test
    fun `disabled facade is safe`() {
        ApmDiagnostics.initialize(
            directory = tempDir,
            config = DiagnosticsConfig(enabled = false),
            processName = "com.example",
            sessionId = "disabled"
        )

        assertEquals(DiagnosticStatus.INACTIVE, ApmDiagnostics.status())
        assertTrue(ApmDiagnostics.snapshot().isEmpty())
        assertFalse(ApmDiagnostics.clear())
    }

    /** Initialized facade must support local snapshot, export, and clear. */
    @Test
    fun `facade completes local support workflow`() {
        ApmDiagnostics.initialize(
            directory = tempDir,
            config = DiagnosticsConfig(),
            processName = "com.example",
            sessionId = "session"
        )
        ApmDiagnostics.record(DiagnosticLevel.ERROR, "core", "test", "failure", null)
        assertTrue(ApmDiagnostics.flush(1_000L))

        val target = File(tempDir, "support.zip")
        val result = ApmDiagnostics.exportTo(target)

        assertEquals("failure", ApmDiagnostics.snapshot(1).single().message)
        assertTrue(result.success)
        assertTrue(target.exists())
        assertTrue(ApmDiagnostics.clear())
        assertTrue(ApmDiagnostics.snapshot().isEmpty())
    }

    /** Reinitialization in one process must create a distinct diagnostic session identity. */
    @Test
    fun `application initialization creates a new diagnostic session`() {
        val application = RuntimeEnvironment.getApplication()
        ApmDiagnostics.initialize(application, DiagnosticsConfig(), "com.example")
        ApmDiagnostics.record(DiagnosticLevel.INFO, "core", "first", "first", null)
        assertTrue(ApmDiagnostics.flush(1_000L))
        val firstSession = ApmDiagnostics.snapshot(1).single().sessionId

        ApmDiagnostics.initialize(application, DiagnosticsConfig(), "com.example")
        ApmDiagnostics.record(DiagnosticLevel.INFO, "core", "second", "second", null)
        assertTrue(ApmDiagnostics.flush(1_000L))
        val secondSession = ApmDiagnostics.snapshot(1).single().sessionId

        assertFalse(firstSession == secondSession)
    }

    /** Process directory names must be deterministic and collision-resistant after sanitization. */
    @Test
    fun `process directories are isolated`() {
        val first = ApmDiagnostics.processDirectory(tempDir, "com.example:worker/a")
        val repeat = ApmDiagnostics.processDirectory(tempDir, "com.example:worker/a")
        val collision = ApmDiagnostics.processDirectory(tempDir, "com.example:worker?a")

        assertEquals(first.canonicalPath, repeat.canonicalPath)
        assertFalse(first.canonicalPath == collision.canonicalPath)
        assertEquals(tempDir.canonicalPath, first.parentFile?.canonicalPath)
    }

    /** Aggregate export must retain journals written by different Android processes. */
    @Test
    fun `aggregate export includes every process directory`() {
        ApmDiagnostics.initialize(tempDir, DiagnosticsConfig(), "com.example", "main", isolateProcess = true)
        ApmDiagnostics.record(DiagnosticLevel.INFO, "core", null, "main-record", null)
        assertTrue(ApmDiagnostics.flush(1_000L))
        ApmDiagnostics.initialize(tempDir, DiagnosticsConfig(), "com.example:worker", "worker", isolateProcess = true)
        ApmDiagnostics.record(DiagnosticLevel.INFO, "core", null, "worker-record", null)
        assertTrue(ApmDiagnostics.flush(1_000L))

        val target = File(tempDir.parentFile, "aggregate-${System.nanoTime()}.zip")
        try {
            val result = ApmDiagnostics.exportTo(target)

            assertTrue(result.success)
            assertEquals(2, result.exportedRecords)
            ZipFile(target).use { zip ->
                val journal = zip.getInputStream(zip.getEntry("diagnostics.jsonl")).bufferedReader().use { it.readText() }
                assertTrue(journal.contains("main-record"))
                assertTrue(journal.contains("worker-record"))
            }
        } finally {
            target.delete()
        }
    }

    /** Async snapshot must run on the caller-selected executor and deliver one result. */
    @Test
    fun `snapshot async invokes callback`() {
        ApmDiagnostics.initialize(tempDir, DiagnosticsConfig(), "com.example", "async")
        ApmDiagnostics.record(DiagnosticLevel.INFO, "core", null, "async-record", null)
        val executor = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(1)
        var messages = emptyList<String>()
        try {
            ApmDiagnostics.snapshotAsync(executor, 1) { entries ->
                messages = entries.map(DiagnosticEntry::message)
                completed.countDown()
            }

            assertTrue(completed.await(2L, TimeUnit.SECONDS))
            assertEquals(listOf("async-record"), messages)
        } finally {
            executor.shutdownNow()
        }
    }

    /** Async export must create the archive and deliver the result on the selected worker. */
    @Test
    fun `export async invokes callback`() {
        ApmDiagnostics.initialize(tempDir, DiagnosticsConfig(), "com.example", "async-export")
        ApmDiagnostics.record(DiagnosticLevel.INFO, "core", null, "export-record", null)
        val executor = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(1)
        val target = File(tempDir, "async-support.zip")
        var success = false
        try {
            ApmDiagnostics.exportToAsync(executor, target) { result ->
                success = result.success
                completed.countDown()
            }

            assertTrue(completed.await(2L, TimeUnit.SECONDS))
            assertTrue(success)
            assertTrue(target.exists())
        } finally {
            executor.shutdownNow()
        }
    }
}
