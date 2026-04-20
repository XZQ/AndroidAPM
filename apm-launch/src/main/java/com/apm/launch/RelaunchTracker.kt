package com.apm.launch

/**
 * 热启动/温启动恢复过程跟踪器。
 * 使用后台停留时长决定类型，使用前台恢复链路时长决定上报耗时。
 */
internal class RelaunchTracker(
    /** 热温启动分界阈值。 */
    private val warmStartThresholdMs: Long,
    /** 允许上报的最大恢复耗时。 */
    private val launchTimeoutMs: Long
) {

    /** 上一次所有 Activity 都停止的时间点。 */
    private var lastStoppedAtMs: Long? = null

    /** 待完成的恢复类型。 */
    private var pendingLaunchType: String? = null

    /** 本次恢复开始时间。 */
    private var relaunchStartAtMs: Long = 0L

    /** 本次恢复对应的后台停留时长。 */
    private var pendingBackgroundDurationMs: Long = 0L

    /**
     * 记录应用完全退到后台的时间点。
     *
     * @param nowMs 当前时间
     */
    fun onAllActivitiesStopped(nowMs: Long) {
        lastStoppedAtMs = nowMs
    }

    /**
     * 在恢复路径的第一个 Activity.start 时启动计时。
     *
     * @param nowMs 当前时间
     */
    fun onActivityStarted(nowMs: Long) {
        val stoppedAtMs = lastStoppedAtMs ?: return
        val backgroundDurationMs = nowMs - stoppedAtMs
        lastStoppedAtMs = null

        // 超出上报窗口的恢复不再继续跟踪。
        if (backgroundDurationMs >= launchTimeoutMs) {
            pendingLaunchType = null
            return
        }

        pendingBackgroundDurationMs = backgroundDurationMs
        pendingLaunchType = if (backgroundDurationMs < warmStartThresholdMs) {
            LAUNCH_TYPE_HOT
        } else {
            LAUNCH_TYPE_WARM
        }
        relaunchStartAtMs = nowMs
    }

    /**
     * 在恢复路径的首个 Activity.resume 时生成恢复度量。
     *
     * @param nowMs 当前时间
     * @return 热启动/温启动测量结果；若当前没有待完成恢复则返回 null
     */
    fun onActivityResumed(nowMs: Long): RelaunchMeasurement? {
        val launchType = pendingLaunchType ?: return null
        pendingLaunchType = null

        val launchDurationMs = nowMs - relaunchStartAtMs
        // 恢复路径本身超时则不再上报。
        if (launchDurationMs < 0L || launchDurationMs >= launchTimeoutMs) {
            return null
        }

        return RelaunchMeasurement(
            launchType = launchType,
            launchDurationMs = launchDurationMs,
            backgroundDurationMs = pendingBackgroundDurationMs
        )
    }

    /**
     * 恢复度量结果。
     *
     * @property launchType 启动类型：hot/warm
     * @property launchDurationMs 前台恢复路径耗时
     * @property backgroundDurationMs 后台停留时长
     */
    data class RelaunchMeasurement(
        /** 启动类型。 */
        val launchType: String,
        /** 前台恢复链路耗时。 */
        val launchDurationMs: Long,
        /** 后台停留时长。 */
        val backgroundDurationMs: Long
    )

    companion object {
        /** 热启动类型值。 */
        const val LAUNCH_TYPE_HOT = "hot"

        /** 温启动类型值。 */
        const val LAUNCH_TYPE_WARM = "warm"
    }
}
