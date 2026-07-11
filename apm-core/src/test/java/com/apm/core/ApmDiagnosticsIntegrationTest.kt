package com.apm.core

import android.app.Application
import com.apm.core.diagnostics.ApmDiagnostics
import com.apm.core.diagnostics.DiagnosticLevel
import com.apm.core.diagnostics.DiagnosticsConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end self-diagnostics wiring tests through the public APM lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
class ApmDiagnosticsIntegrationTest {

    /** Temporary directory used only to reset the diagnostics singleton before init. */
    private lateinit var resetDirectory: File

    /** Stops prior global state and makes the diagnostics facade inactive. */
    @Before
    fun setUp() {
        Apm.stop()
        resetDirectory = Files.createTempDirectory("apm-diagnostics-reset").toFile()
        ApmDiagnostics.initialize(
            directory = resetDirectory,
            config = DiagnosticsConfig(enabled = false),
            processName = "reset",
            sessionId = "reset"
        )
    }

    /** Stops all runtime threads and removes diagnostic test evidence. */
    @After
    fun tearDown() {
        Apm.stop()
        ApmDiagnostics.clear()
        ApmDiagnostics.initialize(
            directory = resetDirectory,
            config = DiagnosticsConfig(enabled = false),
            processName = "reset",
            sessionId = "reset"
        )
        resetDirectory.deleteRecursively()
    }

    /** Init warnings and structured internal errors must reach the independent journal. */
    @Test
    fun `APM lifecycle records structured diagnostics`() {
        val application = RuntimeEnvironment.getApplication() as Application
        Apm.init(
            application,
            ApmConfig(
                debugLogging = false,
                diagnostics = DiagnosticsConfig(),
                storageType = StorageType.FILE,
                enableSelfMonitoring = false,
                enableRetry = false
            )
        )
        Apm.recordInternalError("ipc_write", IOException("disk full"))

        assertTrue(ApmDiagnostics.flush(1_000L))
        val entries = ApmDiagnostics.snapshot(20)
        val internalError = entries.firstOrNull { entry -> entry.code == "ipc_write" }

        assertTrue(ApmDiagnostics.status().enabled)
        assertTrue(entries.any { entry -> entry.message.contains("StorageType.FILE") })
        assertNotNull(internalError)
        assertEquals(DiagnosticLevel.ERROR, internalError?.level)
        assertEquals("core", internalError?.component)
        assertEquals("java.io.IOException", internalError?.exceptionClass)
    }

    /** Scoped loggers must preserve the real subsystem attribution. */
    @Test
    fun `scoped logger attributes diagnostics`() {
        val recorder = ApmDiagnostics.initialize(
            directory = resetDirectory,
            config = DiagnosticsConfig(),
            processName = "com.example",
            sessionId = "scoped"
        )
        val logger = AndroidApmLogger(enabled = false, diagnostics = recorder).withComponent("uploader")

        logger.w("upload warning")
        assertTrue(ApmDiagnostics.flush(1_000L))

        assertEquals("uploader", ApmDiagnostics.snapshot(1).single().component)
    }

    /** One failing module stop must not prevent later modules or infrastructure from stopping. */
    @Test
    fun `stop continues after module failure`() {
        val application = RuntimeEnvironment.getApplication() as Application
        var secondStopped = false
        Apm.register(TestModule("diagnostics-stop-failure", onStop = { throw IOException("stop failed") }))
        Apm.register(TestModule("diagnostics-stop-success", onStop = { secondStopped = true }))
        Apm.init(
            application,
            ApmConfig(
                diagnostics = DiagnosticsConfig(),
                storageType = StorageType.FILE,
                enableSelfMonitoring = false,
                enableRetry = false
            )
        )

        Apm.stop()

        assertTrue(secondStopped)
        assertFalse(Apm.isInitialized())
        assertTrue(ApmDiagnostics.snapshot(20).any { entry -> entry.code == "stop_module_diagnostics-stop-failure" })
    }

    /** Minimal lifecycle module used by shutdown-continuation tests. */
    private class TestModule(
        /** Stable module name. */
        override val name: String,
        /** Test-controlled stop action. */
        private val onStop: () -> Unit
    ) : ApmModule {
        /** Initialization needs no test state. */
        override fun onInitialize(context: ApmContext) = Unit

        /** Start needs no test state. */
        override fun onStart() = Unit

        /** Delegates to the test action. */
        override fun onStop() = onStop.invoke()
    }
}
