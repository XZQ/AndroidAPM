package com.apm.core.selfmonitor

import com.apm.model.ApmPriority
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SDK 自监控组件。
 * 跟踪 APM 框架自身运行指标，用于：
 * - 评估 SDK 对宿主应用的性能影响
 * - 驱动 [AutoThrottle] 自动降级策略
 * - 定期生成 [SdkHealthReport] 上报
 *
 * 线程安全：所有计数器使用 Atomic 类型，无锁更新。
 */
class SdkSelfMonitor(
    /** 健康报告生成间隔（毫秒）。 */
    private val reportIntervalMs: Long = DEFAULT_REPORT_INTERVAL_MS
) {
    /** 发射事件计数。 */
    private val emitCount = AtomicLong(0L)

    /** 丢弃事件计数（限流、队列满等）。 */
    private val dropCount = AtomicLong(0L)

    /** 上传延迟累计（毫秒），用于计算平均值。 */
    private val totalUploadLatencyMs = AtomicLong(0L)

    /** 上传次数计数，用于计算平均延迟。 */
    private val uploadCount = AtomicLong(0L)

    /** 最大单次上传延迟（毫秒）。 */
    private val maxUploadLatencyMs = AtomicLong(0L)

    /** 当前队列大小快照（由外部定期更新）。 */
    private val currentQueueSize = AtomicInteger(0)

    /**
     * 记录一次事件发射。
     * 每次 [com.apm.core.Apm.emit] 被调用时计数 +1。
     */
    fun recordEmit() {
        emitCount.incrementAndGet()
    }

    /**
     * 记录一次事件丢弃。
     * 限流拦截、队列满丢弃时调用。
     *
     * @param priority 被丢弃事件的优先级，用于分类统计
     */
    fun recordDrop(priority: ApmPriority = ApmPriority.NORMAL) {
        dropCount.incrementAndGet()
    }

    /**
     * 记录一次上传延迟。
     * 每次上传完成时调用，延迟为提交到上传完成的时间差。
     *
     * @param ms 上传延迟毫秒数
     */
    fun recordUploadLatency(ms: Long) {
        totalUploadLatencyMs.addAndGet(ms)
        uploadCount.incrementAndGet()
        // CAS 更新最大延迟
        var prev: Long
        do {
            prev = maxUploadLatencyMs.get()
            if (ms <= prev) break
        } while (!maxUploadLatencyMs.compareAndSet(prev, ms))
    }

    /**
     * 更新队列大小快照。
     * 由分发器定期调用，反映当前积压程度。
     *
     * @param size 当前队列中的事件数
     */
    fun updateQueueSize(size: Int) {
        currentQueueSize.set(size)
    }

    /**
     * 生成当前周期的健康报告。
     * 读取所有计数器快照并重置归零（用于下一周期）。
     *
     * @return 当前周期的健康报告
     */
    fun generateReport(): SdkHealthReport {
        // 读取并重置计数器
        val emit = emitCount.getAndSet(0L)
        val drop = dropCount.getAndSet(0L)
        val totalLatency = totalUploadLatencyMs.getAndSet(0L)
        val uploads = uploadCount.getAndSet(0L)
        val maxLatency = maxUploadLatencyMs.getAndSet(0L)

        // 计算平均延迟
        val avgLatency = if (uploads > 0L) totalLatency / uploads else 0L

        return SdkHealthReport(
            emitCount = emit,
            dropCount = drop,
            queueSize = currentQueueSize.get(),
            avgUploadLatencyMs = avgLatency,
            maxUploadLatencyMs = maxLatency
        )
    }

    /**
     * 获取累计发射事件数（非重置）。
     * 用于外部查询当前总发射量。
     */
    fun getTotalEmitCount(): Long = emitCount.get()

    /**
     * 获取累计丢弃事件数（非重置）。
     * 用于外部查询当前总丢弃量。
     */
    fun getTotalDropCount(): Long = dropCount.get()

    companion object {
        /** 默认报告间隔：60 秒。 */
        private const val DEFAULT_REPORT_INTERVAL_MS = 60_000L
    }
}
