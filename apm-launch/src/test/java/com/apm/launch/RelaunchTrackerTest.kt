package com.apm.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RelaunchTracker 热温启动计时测试。
 * 验证上报耗时基于恢复过程，而不是后台停留时长。
 */
class RelaunchTrackerTest {

    /** 短后台停留应归类为热启动，且耗时取 start 到 resume。 */
    @Test
    fun `hot relaunch uses resume duration instead of background duration`() {
        val tracker = RelaunchTracker(
            warmStartThresholdMs = 5_000L,
            launchTimeoutMs = 30_000L
        )

        tracker.onAllActivitiesStopped(nowMs = 1_000L)
        tracker.onActivityStarted(nowMs = 2_500L)
        val measurement = tracker.onActivityResumed(nowMs = 2_650L)

        assertEquals(RelaunchTracker.LAUNCH_TYPE_HOT, measurement?.launchType)
        assertEquals(150L, measurement?.launchDurationMs)
        assertEquals(1_500L, measurement?.backgroundDurationMs)
    }

    /** 长后台停留应归类为温启动，但上报耗时仍只取恢复路径。 */
    @Test
    fun `warm relaunch keeps classification but reports foreground restore cost`() {
        val tracker = RelaunchTracker(
            warmStartThresholdMs = 5_000L,
            launchTimeoutMs = 30_000L
        )

        tracker.onAllActivitiesStopped(nowMs = 1_000L)
        tracker.onActivityStarted(nowMs = 9_000L)
        val measurement = tracker.onActivityResumed(nowMs = 9_320L)

        assertEquals(RelaunchTracker.LAUNCH_TYPE_WARM, measurement?.launchType)
        assertEquals(320L, measurement?.launchDurationMs)
        assertEquals(8_000L, measurement?.backgroundDurationMs)
    }

    /** 超过超时窗口的恢复不应上报。 */
    @Test
    fun `relaunch over timeout is ignored`() {
        val tracker = RelaunchTracker(
            warmStartThresholdMs = 5_000L,
            launchTimeoutMs = 1_000L
        )

        tracker.onAllActivitiesStopped(nowMs = 1_000L)
        tracker.onActivityStarted(nowMs = 1_800L)

        val measurement = tracker.onActivityResumed(nowMs = 3_000L)

        assertNull(measurement)
    }
}
