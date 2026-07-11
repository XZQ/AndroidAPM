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
    val lastFailure: String?
) {
    companion object {
        /** Status returned before diagnostics initialization. */
        val INACTIVE = DiagnosticStatus(
            enabled = false,
            fileSinkHealthy = false,
            queueDepth = 0,
            retainedBytes = 0L,
            droppedRecords = 0L,
            writeFailures = 0L,
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
