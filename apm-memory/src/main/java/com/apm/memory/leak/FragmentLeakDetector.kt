package com.apm.memory.leak

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/** Checks final Fragment destruction; destroying a View alone does not end a Fragment's lifetime. */
internal class FragmentLeakDetector(
    activity: Activity,
    /** Optional module-owned queue shared with Activity checks. */
    checkQueue: LeakCheckQueue? = null,
    /** Suspected-retention result sink. */
    private val onLeakFound: (LeakResult) -> Unit
) : FragmentManager.FragmentLifecycleCallbacks() {
    /** Immutable scene label avoids retaining the host Activity in pending checks. */
    private val scene = activity.javaClass.simpleName
    /** Whether this standalone detector owns its queue. */
    private val ownsQueue = checkQueue == null
    /** Shared background queue, never a main-looper IdleHandler. */
    private val checks = checkQueue ?: LeakCheckQueue()
    /** Opaque cancellation token with no host reference. */
    private val owner = Any()
    /** Permanent closure prevents callbacks crossing a module session. */
    private var closed = false

    /** Registers recursive lifecycle observation. */
    fun register(supportFragmentManager: FragmentManager) {
        supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
    }

    /** Cancels delayed/in-progress watches before unregistering lifecycle observation. */
    @Synchronized
    fun unregister(supportFragmentManager: FragmentManager) {
        closed = true
        checks.cancel(owner)
        if (ownsQueue) checks.shutdown()
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
    }

    /** A back-stack Fragment may legitimately outlive its destroyed View; do not check it here. */
    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = Unit

    /** Only final Fragment destruction begins the post-destruction grace period. */
    @Synchronized
    override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
        if (!closed) checks.watch(f, LeakType.FRAGMENT, scene, owner, onLeakFound)
    }
}
