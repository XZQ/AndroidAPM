package com.apm.core.selfmonitor

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import java.util.Locale

/**
 * SDK 健康报告数据类。
 * 汇总一次采集周期内的自监控指标，用于：
 * - 评估 SDK 自身运行健康度
 * - 驱动自动降级策略（[AutoThrottle]）
 * - 作为 APM 事件上报到服务端供监控大盘展示
 *
 * 所有计数器通过快照读取，非实时。
 */
data class SdkHealthReport(
    /** 采集周期内发射的事件总数。 */
    val emitCount: Long,
    /** 采集周期内被丢弃的事件数。 */
    val dropCount: Long,
    /** 当前上传队列大小。 */
    val queueSize: Int,
    /** 采集周期内平均上传延迟（毫秒）。 */
    val avgUploadLatencyMs: Long,
    /** 采集周期内最大上传延迟（毫秒）。 */
    val maxUploadLatencyMs: Long,
    /** 采集周期内 SDK 内部错误数（监控模块降级处理的异常）。 */
    val internalErrorCount: Long = 0L,
    /** Dispatcher drops caused by one NORMAL/LOW module exceeding its pressure-time queue share. */
    val dispatcherModuleIsolationDropCount: Long = 0L,
    /** Diagnostics records dropped by bounded queue pressure. */
    val diagnosticDroppedCount: Long = 0L,
    /** Diagnostics file-sink failures. */
    val diagnosticWriteFailureCount: Long = 0L,
    /** 报告生成时间戳（毫秒）。 */
    val reportTimestamp: Long = System.currentTimeMillis(),
    /** Complete period loss counts keyed by stable [SdkDropReason] names. */
    val dropCountsByReason: Map<String, Long> = emptyMap(),
    /** Complete period loss counts keyed by priority names plus `UNATTRIBUTED`. */
    val dropCountsByPriority: Map<String, Long> = emptyMap()
) {
    /**
     * 计算事件丢弃率（0.0 ~ 1.0）。
     * emitCount 为 0 时返回 0。
     */
    val dropRate: Float
        get() = if (emitCount > 0L) dropCount.toFloat() / emitCount.toFloat() else 0f

    /**
     * 将健康报告转换为 APM 事件。
     * 以 METRIC 类型上报，包含所有自监控指标字段。
     *
     * @return 可直接通过 [com.apm.core.Apm.emit] 发送的 APM 事件
     */
    fun toApmEvent(): ApmEvent {
        return ApmEvent(
            module = MODULE_NAME,
            name = EVENT_SDK_HEALTH,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            priority = ApmPriority.HIGH,
            timestamp = reportTimestamp,
            fields = healthFields(String.format(Locale.ROOT, "%.4f", dropRate))
        )
    }

    /** Returns the complete field map used by the runtime `core/sdk_health` event. */
    internal fun toCoreHealthFields(): Map<String, Any> = healthFields(dropRate)

    /** Builds fixed aggregate fields plus deterministic flattened reason and priority counters. */
    private fun healthFields(dropRateValue: Any): Map<String, Any> {
        val fields = linkedMapOf<String, Any>(
            FIELD_EMIT_COUNT to emitCount,
            FIELD_DROP_COUNT to dropCount,
            FIELD_DROP_RATE to dropRateValue,
            FIELD_QUEUE_SIZE to queueSize,
            FIELD_AVG_UPLOAD_LATENCY_MS to avgUploadLatencyMs,
            FIELD_MAX_UPLOAD_LATENCY_MS to maxUploadLatencyMs,
            FIELD_INTERNAL_ERROR_COUNT to internalErrorCount,
            FIELD_DISPATCHER_MODULE_ISOLATION_DROP_COUNT to dispatcherModuleIsolationDropCount,
            FIELD_DIAGNOSTIC_DROPPED_COUNT to diagnosticDroppedCount,
            FIELD_DIAGNOSTIC_WRITE_FAILURE_COUNT to diagnosticWriteFailureCount
        )
        for (reason in SdkDropReason.values()) {
            fields["$FIELD_DROP_REASON_PREFIX${reason.name.lowercase(Locale.ROOT)}"] =
                dropCountsByReason[reason.name] ?: 0L
        }
        for (priority in ApmPriority.values()) {
            fields["$FIELD_DROP_PRIORITY_PREFIX${priority.name.lowercase(Locale.ROOT)}"] =
                dropCountsByPriority[priority.name] ?: 0L
        }
        fields["$FIELD_DROP_PRIORITY_PREFIX$UNATTRIBUTED_PRIORITY_FIELD"] =
            dropCountsByPriority[SdkSelfMonitor.UNATTRIBUTED_PRIORITY] ?: 0L
        return fields
    }

    /** Returns a bounded, payload-free summary suitable for the independent diagnostics journal. */
    internal fun toDiagnosticSummary(): String = buildString {
        append(FIELD_EMIT_COUNT).append('=').append(emitCount)
        append(' ').append(FIELD_DROP_COUNT).append('=').append(dropCount)
        append(' ').append(FIELD_DROP_RATE).append('=').append(String.format(Locale.ROOT, "%.4f", dropRate))
        append(' ').append(FIELD_QUEUE_SIZE).append('=').append(queueSize)
        append(' ').append(FIELD_AVG_UPLOAD_LATENCY_MS).append('=').append(avgUploadLatencyMs)
        append(' ').append(FIELD_MAX_UPLOAD_LATENCY_MS).append('=').append(maxUploadLatencyMs)
        append(' ').append(FIELD_INTERNAL_ERROR_COUNT).append('=').append(internalErrorCount)
        append(' ').append(FIELD_DISPATCHER_MODULE_ISOLATION_DROP_COUNT).append('=')
            .append(dispatcherModuleIsolationDropCount)
        append(' ').append(FIELD_DIAGNOSTIC_DROPPED_COUNT).append('=').append(diagnosticDroppedCount)
        append(' ').append(FIELD_DIAGNOSTIC_WRITE_FAILURE_COUNT).append('=').append(diagnosticWriteFailureCount)
        for (reason in SdkDropReason.values()) {
            append(' ').append(FIELD_DROP_REASON_PREFIX).append(reason.name.lowercase(Locale.ROOT))
                .append('=').append(dropCountsByReason[reason.name] ?: 0L)
        }
        for (priority in ApmPriority.values()) {
            append(' ').append(FIELD_DROP_PRIORITY_PREFIX).append(priority.name.lowercase(Locale.ROOT))
                .append('=').append(dropCountsByPriority[priority.name] ?: 0L)
        }
        append(' ').append(FIELD_DROP_PRIORITY_PREFIX).append(UNATTRIBUTED_PRIORITY_FIELD)
            .append('=').append(dropCountsByPriority[SdkSelfMonitor.UNATTRIBUTED_PRIORITY] ?: 0L)
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "sdk_self_monitor"
        /** 健康报告事件名。 */
        private const val EVENT_SDK_HEALTH = "sdk_health_report"
        /** 字段：发射事件数。 */
        private const val FIELD_EMIT_COUNT = "emitCount"
        /** 字段：丢弃事件数。 */
        private const val FIELD_DROP_COUNT = "dropCount"
        /** 字段：队列大小。 */
        private const val FIELD_QUEUE_SIZE = "queueSize"
        /** 字段：丢弃率。 */
        private const val FIELD_DROP_RATE = "dropRate"
        /** 字段：平均上传延迟。 */
        private const val FIELD_AVG_UPLOAD_LATENCY_MS = "avgUploadLatencyMs"
        /** 字段：最大上传延迟。 */
        private const val FIELD_MAX_UPLOAD_LATENCY_MS = "maxUploadLatencyMs"
        /** 字段：内部错误数。 */
        private const val FIELD_INTERNAL_ERROR_COUNT = "internalErrorCount"
        /** Field: dispatcher drops caused by noisy-neighbor module isolation. */
        private const val FIELD_DISPATCHER_MODULE_ISOLATION_DROP_COUNT =
            "dispatcherModuleIsolationDropCount"
        /** Field: diagnostics records dropped before file persistence. */
        private const val FIELD_DIAGNOSTIC_DROPPED_COUNT = "diagnosticDroppedCount"
        /** Field: diagnostics file-sink failures. */
        private const val FIELD_DIAGNOSTIC_WRITE_FAILURE_COUNT = "diagnosticWriteFailureCount"
        /** Prefix for one stable drop-reason counter. */
        private const val FIELD_DROP_REASON_PREFIX = "dropReason."
        /** Prefix for one priority or unattributed drop counter. */
        private const val FIELD_DROP_PRIORITY_PREFIX = "dropPriority."
        /** Lowercase wire field suffix for aggregate-only loss results. */
        private const val UNATTRIBUTED_PRIORITY_FIELD = "unattributed"
    }
}

/**
 * Publishes one health report to the independent local journal and the normal telemetry channel.
 *
 * Recoverable diagnostics failures are intentionally isolated without recursive error reporting;
 * the high-priority telemetry attempt must still execute.
 */
internal inline fun publishSdkHealthReport(
    report: SdkHealthReport,
    diagnosticsSink: (String) -> Unit,
    eventSink: (ApmPriority, Map<String, Any>) -> Unit
) {
    try {
        diagnosticsSink(report.toDiagnosticSummary())
    } catch (_: Exception) {
        // The independent diagnostics sink is the terminal error boundary and cannot report itself.
    }
    eventSink(ApmPriority.HIGH, report.toCoreHealthFields())
}
