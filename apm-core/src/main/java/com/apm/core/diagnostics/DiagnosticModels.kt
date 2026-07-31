package com.apm.core.diagnostics

import java.io.File

/** Severity of an SDK diagnostic record. */
enum class DiagnosticLevel {
    /** Development detail controlled by the debug logging switch. */
    DEBUG,
    /** Safe SDK lifecycle or state information. */
    INFO,
    /** Recoverable SDK degradation. */
    WARN,
    /** Failed SDK operation or caught exception. */
    ERROR
}

/** One sanitized SDK diagnostic record. */
data class DiagnosticEntry(
    /** Monotonic sequence within the current diagnostics runtime. */
    val sequence: Long,
    /** Wall-clock creation time in epoch milliseconds. */
    val timestampMs: Long,
    /** Process-level SDK session identifier. */
    val sessionId: String,
    /** Diagnostic severity. */
    val level: DiagnosticLevel,
    /** SDK component that produced the record. */
    val component: String,
    /** Stable machine-readable error code when available. */
    val code: String?,
    /** Sanitized bounded human-readable message. */
    val message: String,
    /** Android process name. */
    val processName: String,
    /** Producing thread name. */
    val threadName: String,
    /** Exception class name when a throwable exists. */
    val exceptionClass: String?,
    /** Sanitized exception message. */
    val exceptionMessage: String?,
    /** Bounded exception stack trace. */
    val stackTrace: String?,
    /** Stable hash of the retained stack trace. */
    val stackHash: String?
)

/** Current health and resource usage of the diagnostics runtime. */
data class DiagnosticStatus(
    /** Whether the diagnostics runtime is enabled. */
    val enabled: Boolean,
    /** Whether the rolling file sink currently accepts writes. */
    val fileSinkHealthy: Boolean,
    /** Current bounded writer-queue depth. */
    val queueDepth: Int,
    /** Total bytes retained by diagnostic segments. */
    val retainedBytes: Long,
    /** Records dropped by bounded queue pressure. */
    val droppedRecords: Long,
    /** File-sink write failures. */
    val writeFailures: Long,
    /** Corrupt persisted lines skipped while reading. */
    val corruptRecords: Long,
    /** Last sanitized sink failure summary. */
    val lastFailure: String?,
    /** Current encoded bytes waiting in the writer queue. */
    val queueBytes: Long = 0L,
    /** Current encoded bytes retained by the memory ring. */
    val memoryBytes: Long = 0L,
    /** Persisted-journal read failures. */
    val readFailures: Long = 0L
) {
    companion object {
        /** Status returned before diagnostics initialization. */
        val INACTIVE = DiagnosticStatus(
            enabled = false,
            fileSinkHealthy = false,
            queueDepth = 0,
            queueBytes = 0L,
            memoryBytes = 0L,
            retainedBytes = 0L,
            droppedRecords = 0L,
            writeFailures = 0L,
            readFailures = 0L,
            corruptRecords = 0L,
            lastFailure = null
        )
    }
}

/** Result of a bounded diagnostics ZIP export. */
data class DiagnosticExportResult(
    /** Whether export completed successfully. */
    val success: Boolean,
    /** Created ZIP file, or null on failure. */
    val file: File?,
    /** Number of readable records written to the ZIP. */
    val exportedRecords: Int,
    /** Sanitized failure description, or null on success. */
    val errorMessage: String?
)

/**
 * Explicit host integration surfaces whose runtime wiring cannot be inferred from module registration alone.
 *
 * Each point deliberately identifies a capability rather than a particular third-party library. For example,
 * [NETWORK] can be observed through the OkHttp interceptor/listener, the HttpURLConnection helper, or the
 * manual completion callback.
 */
enum class HostIntegrationPoint {
    /** Host HTTP client instrumentation. */
    NETWORK,
    /** Host SQLite wrapper or execution callbacks. */
    SQLITE,
    /** Host Binder/AIDL tracing or completion callbacks. */
    IPC,
    /** Per-instance WebView installation, delegate wrappers, or callbacks. */
    WEBVIEW,
    /** Explicit host ThreadPoolExecutor registration. */
    THREAD_POOL,
    /** Host WakeLock, GPS, or Alarm callbacks. */
    BATTERY,
    /** Explicit stream wrappers, native hook, or manual IO callbacks. */
    IO
}

/** Derived readiness state for one explicit host integration point. */
enum class HostIntegrationState {
    /** The owning monitor is not running in the current process. */
    MODULE_INACTIVE,
    /** The monitor is running, but no installation or operation has been observed in this session. */
    NO_RUNTIME_EVIDENCE,
    /** At least one current installation or registration exists, but no operation has been observed yet. */
    REGISTRATION_ACTIVE,
    /** One or more operations were observed, with no current installation count available. */
    OBSERVED,
    /** A current installation or registration exists and operations have also been observed. */
    REGISTRATION_ACTIVE_AND_OBSERVED
}

/** Runtime-only, value-free evidence for one explicit host integration point. */
data class HostIntegrationStatus(
    /** Stable integration capability. */
    val point: HostIntegrationPoint,
    /** Whether the owning monitoring module is running in this process. */
    val moduleActive: Boolean,
    /** Current explicit installations or registrations known to the SDK. */
    val activeRegistrations: Int,
    /** Number of accepted integration signals observed in the current SDK session. */
    val observedSignals: Long,
    /** Epoch millisecond time of the latest signal, or null when none was observed. */
    val lastObservedAtMs: Long?,
    /** State derived from module, registration, and observation evidence. */
    val state: HostIntegrationState
)

/** Immutable point-in-time view of explicit host integration evidence. */
data class HostIntegrationSnapshot(
    /** Epoch millisecond time at which the snapshot was captured. */
    val capturedAtMs: Long,
    /** Stable enum-order status for every supported integration point. */
    val integrations: List<HostIntegrationStatus>
)
