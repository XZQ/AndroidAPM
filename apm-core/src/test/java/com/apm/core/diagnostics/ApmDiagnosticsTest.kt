package com.apm.core.diagnostics

import java.io.File
import java.nio.file.Files
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
}
