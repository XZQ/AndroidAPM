package com.apm.memory.leak

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Watches destroyed Activities through weak references and a bounded background queue. */
internal class ActivityLeakDetector(
    /** Grace period after onDestroy. */
    checkDelayMs: Long = DEFAULT_CHECK_DELAY_MS,
    /** Optional module-owned queue shared with Fragment detectors. */
    checkQueue: LeakCheckQueue? = null,
    /** Suspected-retention result sink. */
    private val onLeakFound: (LeakResult) -> Unit
) : Application.ActivityLifecycleCallbacks {
    /** Queue ownership is explicit so one detector cannot stop its peers. */
    private val ownsQueue = checkQueue == null
    /** Shared or standalone bounded queue. */
    private val checks = checkQueue ?: LeakCheckQueue(checkDelayMs)
    /** Opaque cancellation token; it never references an Activity. */
    private val owner = Any()
    /** Permanent detector closure. */
    private var closed = false

    /** Captures class/scene text synchronously; delayed work contains no strong Activity reference. */
    @Synchronized
    override fun onActivityDestroyed(activity: Activity) {
        if (!closed) checks.watch(activity, LeakType.ACTIVITY, activity.javaClass.simpleName, owner, onLeakFound)
    }

    /** Removes this detector's watches before releasing its worker, if owned. */
    @Synchronized
    fun shutdown() {
        closed = true
        checks.cancel(owner)
        if (ownsQueue) checks.shutdown()
    }

    /** Creation does not start retention checks. */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    /** Started Activities are legitimately retained. */
    override fun onActivityStarted(activity: Activity) = Unit
    /** Scene is captured at destruction, not sampled later. */
    override fun onActivityResumed(activity: Activity) = Unit
    /** Paused Activities are legitimately retained. */
    override fun onActivityPaused(activity: Activity) = Unit
    /** Stopped Activities are legitimately retained. */
    override fun onActivityStopped(activity: Activity) = Unit
    /** Saving state does not imply destruction. */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        /** Default grace period. */
        private const val DEFAULT_CHECK_DELAY_MS = 5_000L
    }
}
