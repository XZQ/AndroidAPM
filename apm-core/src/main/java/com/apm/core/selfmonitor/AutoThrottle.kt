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
 * 返回建议禁用的模块名列表，由上层 [com.apm.core.Apm] 执行。
 */
object AutoThrottle {

    /**
     * 根据健康报告计算需要禁用的模块列表。
     *
     * @param report 当前周期的健康报告
     * @return 建议禁用的模块名列表，空列表表示无需降级
     */
    fun computeModulesToDisable(report: SdkHealthReport): List<String> {
        val modules = mutableListOf<String>()

        // 策略 1：丢弃率超过阈值，禁用 LOW 模块
        if (report.dropRate > DROP_RATE_THRESHOLD_LOW) {
            modules.addAll(LOW_PRIORITY_MODULES)
        }

        // 策略 2：上传延迟过高，禁用 LOW 模块
        if (report.avgUploadLatencyMs > UPLOAD_LATENCY_THRESHOLD_MS) {
            // 避免重复添加
            for (mod in LOW_PRIORITY_MODULES) {
                if (mod !in modules) {
                    modules.add(mod)
                }
            }
        }

        // 策略 3：丢弃率极高，额外禁用 NORMAL 模块
        if (report.dropRate > DROP_RATE_THRESHOLD_HIGH) {
            for (mod in NORMAL_PRIORITY_MODULES) {
                if (mod !in modules) {
                    modules.add(mod)
                }
            }
        }

        return modules
    }

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
}
