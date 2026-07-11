package com.apm.core.diagnostics

import android.app.Application
import com.apm.core.ProcessSessionId
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Public local support facade for AndroidAPM self-diagnostics.
 */
object ApmDiagnostics {

    /** Active or most recently stopped diagnostics recorder. */
    private val recorder = AtomicReference<DiagnosticRecorder?>(null)

    /** Returns current diagnostics health and resource usage. */
    fun status(): DiagnosticStatus = recorder.get()?.status() ?: DiagnosticStatus.INACTIVE

    /**
     * Returns newest-first local diagnostic entries.
     *
     * @param limit requested maximum entries
     */
    fun snapshot(limit: Int = DEFAULT_SNAPSHOT_LIMIT): List<DiagnosticEntry> {
        return recorder.get()?.snapshot(limit).orEmpty()
    }

    /**
     * Exports a bounded ZIP package. Call from a worker thread.
     *
     * @param targetFile app-selected export target
     */
    fun exportTo(targetFile: File): DiagnosticExportResult {
        return recorder.get()?.exportTo(targetFile) ?: DiagnosticExportResult(
            success = false,
            file = null,
            exportedRecords = 0,
            errorMessage = NOT_INITIALIZED_MESSAGE
        )
    }

    /** Clears all retained local diagnostic records. */
    fun clear(): Boolean = recorder.get()?.clear() ?: false

    /** Initializes diagnostics in the default AndroidAPM app-private directory. */
    internal fun initialize(
        application: Application,
        config: DiagnosticsConfig,
        processName: String
    ): DiagnosticRecorder? {
        val directory = File(application.filesDir, DIAGNOSTICS_DIRECTORY)
        return initialize(directory, config, processName, ProcessSessionId.get())
    }

    /** Initializes diagnostics with an explicit storage directory and identity. */
    internal fun initialize(
        directory: File,
        config: DiagnosticsConfig,
        processName: String,
        sessionId: String
    ): DiagnosticRecorder? {
        val previous = recorder.getAndSet(null)
        previous?.shutdown()
        if (!config.enabled) {
            return null
        }
        val created = DiagnosticRecorder(
            config = config,
            processName = processName,
            sessionId = sessionId,
            store = DiagnosticFileStore(directory, config)
        )
        recorder.set(created)
        return created
    }

    /** Records one structured entry when diagnostics are active. */
    internal fun record(
        level: DiagnosticLevel,
        component: String,
        code: String?,
        message: String,
        error: Throwable?
    ) {
        recorder.get()?.record(level, component, code, message, error)
    }

    /** Flushes accepted records within a caller-selected bound. */
    internal fun flush(timeoutMs: Long): Boolean = recorder.get()?.flush(timeoutMs) ?: true

    /** Stops file persistence while preserving snapshot and export access. */
    internal fun shutdown() {
        recorder.get()?.shutdown()
    }

    /** Default public snapshot size. */
    private const val DEFAULT_SNAPSHOT_LIMIT = 100
    /** App-private diagnostics directory relative to filesDir. */
    private const val DIAGNOSTICS_DIRECTORY = "android-apm/diagnostics"
    /** Failure returned before diagnostics initialization. */
    private const val NOT_INITIALIZED_MESSAGE = "Diagnostics are not initialized"
}
