package com.apm.memory

import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity

/** One fully classified memory event waiting to enter the shared APM pipeline. */
internal data class MemoryReport(
    /** Stable event name within the memory module. */
    val name: String,
    /** Event signal kind. */
    val kind: ApmEventKind,
    /** Diagnostic severity. */
    val severity: ApmSeverity,
    /** Durable storage and upload priority. */
    val priority: ApmPriority,
    /** Optional screen or host scene. */
    val scene: String? = null,
    /** Optional foreground state captured with the signal. */
    val foreground: Boolean? = null,
    /** Bounded event payload. */
    val fields: Map<String, Any?> = emptyMap()
)

/** Receives classified memory reports without coupling detectors to global state in tests. */
internal fun interface MemoryReportSink {
    /** Delivers one report to its configured destination. */
    fun emit(report: MemoryReport)
}

/** Production sink that forwards memory reports into [Apm]. */
internal object ApmMemoryReportSink : MemoryReportSink {
    /** Sends one report through the normal dispatcher and durable outbox. */
    override fun emit(report: MemoryReport) {
        Apm.emit(
            module = MODULE_NAME,
            name = report.name,
            kind = report.kind,
            severity = report.severity,
            priority = report.priority,
            scene = report.scene,
            foreground = report.foreground,
            fields = report.fields
        )
    }

    /** Memory module event namespace. */
    private const val MODULE_NAME = "memory"
}
