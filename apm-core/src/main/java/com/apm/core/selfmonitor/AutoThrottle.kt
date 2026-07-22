package com.apm.core.selfmonitor

/**
 * 自动降级策略。
 * 根据 SDK 健康报告判断是否需要关闭部分模块以降低 SDK 开销。
 *
 * 当前策略：
 * - 丢弃率 > 50%：关闭所有 LOW 优先级模块
 * - 平均上传延迟 > 10 秒：关闭所有 LOW 优先级模块
 * - 丢弃率 > 80%：额外关闭 NORMAL 优先级模块
 *
 * [computeModulesToDisable] 只计算单个周期的降级建议；运行时使用
 * [AutoThrottleController] 保持降级状态，并在连续健康周期后恢复模块。
 */
object AutoThrottle {

    /**
     * 根据健康报告计算需要禁用的模块列表。
     *
     * @param report 当前周期的健康报告
     * @return 建议禁用的模块名列表，空列表表示无需降级
     */
    fun computeModulesToDisable(report: SdkHealthReport): List<String> {
        val modules = linkedSetOf<String>()

        // 策略 1：丢弃率超过阈值，禁用 LOW 模块
        if (report.dropRate > DROP_RATE_THRESHOLD_LOW) {
            modules.addAll(LOW_PRIORITY_MODULES)
        }

        // 策略 2：上传延迟过高，禁用 LOW 模块
        if (report.avgUploadLatencyMs > UPLOAD_LATENCY_THRESHOLD_MS) {
            modules.addAll(LOW_PRIORITY_MODULES)
        }

        // 策略 3：丢弃率极高，额外禁用 NORMAL 模块
        if (report.dropRate > DROP_RATE_THRESHOLD_HIGH) {
            modules.addAll(NORMAL_PRIORITY_MODULES)
        }

        return modules.toList()
    }

    /**
     * 判断一个周期是否达到恢复门槛。
     * 恢复阈值显著低于降级阈值，以避免临界值附近频繁启停模块。
     *
     * @param report 当前周期的健康报告
     * @return true 表示该周期可计入连续健康周期
     */
    internal fun isRecoveryHealthy(report: SdkHealthReport): Boolean =
        report.dropRate <= RECOVERY_DROP_RATE_THRESHOLD &&
            report.avgUploadLatencyMs <= RECOVERY_UPLOAD_LATENCY_THRESHOLD_MS

    /** LOW 优先级模块：电池、GC、线程、渲染、WebView。 */
    private val LOW_PRIORITY_MODULES = listOf(
        "battery",
        "gc_monitor",
        "thread_monitor",
        "render",
        "webview"
    )

    /** NORMAL 优先级模块：FPS、慢方法、IO、网络、IPC、SQLite。 */
    private val NORMAL_PRIORITY_MODULES = listOf(
        "fps",
        "slow_method",
        "io",
        "network",
        "ipc",
        "sqlite"
    )

    /** 丢弃率阈值：50%，超过此值禁用 LOW 模块。 */
    private const val DROP_RATE_THRESHOLD_LOW = 0.5f

    /** 丢弃率阈值：80%，超过此值额外禁用 NORMAL 模块。 */
    private const val DROP_RATE_THRESHOLD_HIGH = 0.8f

    /** 上传延迟阈值：10 秒，超过此值禁用 LOW 模块。 */
    private const val UPLOAD_LATENCY_THRESHOLD_MS = 10_000L

    /** 恢复丢弃率阈值：20%，与 50% 降级阈值形成迟滞区间。 */
    private const val RECOVERY_DROP_RATE_THRESHOLD = 0.2f

    /** 恢复平均上传延迟阈值：3 秒，与 10 秒降级阈值形成迟滞区间。 */
    private const val RECOVERY_UPLOAD_LATENCY_THRESHOLD_MS = 3_000L
}

/**
 * 单次自动降级状态迁移结果。
 *
 * @property modulesToThrottle 本周期结束后仍应保持降级的完整模块集合
 * @property modulesToRecover 本周期达到恢复条件、可重新启动的模块集合
 */
internal data class AutoThrottleDecision(
    val modulesToThrottle: Set<String>,
    val modulesToRecover: Set<String>
)

/**
 * 带迟滞的自动降级状态机。
 *
 * 降级立即生效；恢复需要连续多个健康周期，从而避免丢弃率或上传延迟在阈值附近
 * 波动时反复执行模块 `onStop` / `onStart`。实例由单线程健康检查任务持有。
 */
internal class AutoThrottleController {

    /** 当前由自动降级策略保持关闭的模块名。 */
    private val throttledModules = linkedSetOf<String>()

    /** 最近连续达到恢复门槛的周期数。 */
    private var consecutiveHealthyPeriods = 0

    /**
     * 消费一个健康报告并推进降级状态。
     *
     * @param report 当前周期健康报告
     * @return 应保持关闭和可恢复模块的不可变快照
     */
    fun evaluate(report: SdkHealthReport): AutoThrottleDecision {
        val degradedModules = AutoThrottle.computeModulesToDisable(report)
        if (degradedModules.isNotEmpty()) {
            // 任一退化信号都立即打断恢复计数，并保留此前更高等级的降级范围。
            consecutiveHealthyPeriods = 0
            throttledModules.addAll(degradedModules)
            return currentDecision()
        }

        if (throttledModules.isEmpty()) {
            // 未处于降级状态时无需累计恢复周期。
            consecutiveHealthyPeriods = 0
            return currentDecision()
        }

        if (!AutoThrottle.isRecoveryHealthy(report)) {
            // 迟滞区间既不扩大降级，也不计作健康，避免临界波动触发恢复。
            consecutiveHealthyPeriods = 0
            return currentDecision()
        }

        consecutiveHealthyPeriods += 1
        if (consecutiveHealthyPeriods < REQUIRED_HEALTHY_PERIODS) {
            return currentDecision()
        }

        // 达到连续健康门槛后一次恢复本状态机关闭的全部模块。
        val modulesToRecover = throttledModules.toSet()
        throttledModules.clear()
        consecutiveHealthyPeriods = 0
        return AutoThrottleDecision(emptySet(), modulesToRecover)
    }

    /** 返回当前降级集合的不可变快照。 */
    private fun currentDecision(): AutoThrottleDecision = AutoThrottleDecision(
        modulesToThrottle = throttledModules.toSet(),
        modulesToRecover = emptySet()
    )

    private companion object {
        /** 连续三个健康周期后恢复，默认配置下约为三分钟。 */
        const val REQUIRED_HEALTHY_PERIODS = 3
    }
}
