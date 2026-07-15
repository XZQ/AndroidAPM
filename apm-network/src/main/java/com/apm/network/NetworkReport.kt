package com.apm.network

import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity

/** Immutable network event passed from collection logic to the APM runtime. */
internal data class NetworkReport(
    /** Event name within the network module. */
    val name: String,
    /** Semantic event kind used by downstream processors. */
    val kind: ApmEventKind,
    /** Event severity. */
    val severity: ApmSeverity,
    /** Delivery priority. */
    val priority: ApmPriority = ApmPriority.NORMAL,
    /** Structured event fields. */
    val fields: Map<String, Any?>
)

/** Receives network reports without coupling collection logic to global runtime state. */
internal fun interface NetworkReportSink {
    /** Emits one completed network report. */
    fun emit(report: NetworkReport)
}

/** Production report sink that forwards network reports to [Apm]. */
internal object ApmNetworkReportSink : NetworkReportSink {
    /** Forwards one report while preserving its delivery metadata. */
    override fun emit(report: NetworkReport) {
        Apm.emit(
            module = "network",
            name = report.name,
            kind = report.kind,
            severity = report.severity,
            priority = report.priority,
            fields = report.fields
        )
    }
}
