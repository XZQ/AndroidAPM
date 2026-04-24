package com.apm.core.selfmonitor

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity

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
    /** 报告生成时间戳（毫秒）。 */
    val reportTimestamp: Long = System.currentTimeMillis()
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
            priority = ApmPriority.LOW,
            timestamp = reportTimestamp,
            fields = mapOf(
                FIELD_EMIT_COUNT to emitCount,
                FIELD_DROP_COUNT to dropCount,
                FIELD_QUEUE_SIZE to queueSize,
                FIELD_DROP_RATE to String.format("%.4f", dropRate),
                FIELD_AVG_UPLOAD_LATENCY_MS to avgUploadLatencyMs,
                FIELD_MAX_UPLOAD_LATENCY_MS to maxUploadLatencyMs
            )
        )
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
    }
}
