package com.apm.memory.leak

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.ref.WeakReference
import java.time.Duration

/** Deterministic ownership and lifecycle checks; correctness does not depend on a real GC cycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LifecycleLeakDetectorTest {
    /** The queue and callback contain only weak Activity targets, and stop empties both. */
    @Test
    fun `activity callbacks cannot retain their observed target`() {
        val activity = Activity()
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { }
        val detector = ActivityLeakDetector(checkQueue = checks) { }
        try {
            detector.onActivityDestroyed(activity)
            assertWeakWatch(checks, activity)
            detector.shutdown()
            assertEquals(0, checks.pendingCount())
            assertNull(headCallback(checks.handler))
            detector.onActivityDestroyed(activity)
            assertEquals(0, checks.pendingCount())
        } finally { checks.shutdown() }
    }

    /** Neither the Fragment nor its host Activity can be captured by delayed work. */
    @Test
    fun `fragment callbacks capture metadata and unregister cancels pending work`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val fragment = Fragment()
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { }
        val detector = FragmentLeakDetector(activity, checks) { }
        try {
            detector.onFragmentViewDestroyed(activity.supportFragmentManager, fragment)
            assertEquals(0, checks.pendingCount())
            detector.onFragmentDestroyed(activity.supportFragmentManager, fragment)
            assertWeakWatch(checks, fragment)
            assertTrue(detector.javaClass.declaredFields.none { it.isAccessible = true; it.get(detector) === activity })
            detector.unregister(activity.supportFragmentManager)
            assertEquals(0, checks.pendingCount())
            detector.onFragmentDestroyed(activity.supportFragmentManager, fragment)
            assertEquals(0, checks.pendingCount())
        } finally {
            checks.shutdown()
            controller.pause().stop().destroy()
        }
    }

    /** A real replace/back-stack transition destroys the View while retaining its Fragment legally. */
    @Test
    fun `back stack view destruction does not schedule fragment retention`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { }
        val detector = FragmentLeakDetector(activity, checks) { }
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        val first = TestFragment()
        val manager = activity.supportFragmentManager
        detector.register(manager)
        try {
            manager.beginTransaction().add(container.id, first).commitNow()
            assertTrue(first.view != null)
            manager.beginTransaction().replace(container.id, TestFragment()).addToBackStack(null).commit()
            manager.executePendingTransactions()
            assertNull(first.view)
            assertFalse(first.isDetached)
            assertEquals(1, manager.backStackEntryCount)
            assertEquals(0, checks.pendingCount())
        } finally {
            detector.unregister(manager)
            checks.shutdown()
            controller.pause().stop().destroy()
        }
    }

    /** Lifecycle churn has one pending callback, fixed watch capacity, and one shared GC request. */
    @Test
    fun `background queue bounds and coalesces lifecycle bursts`() {
        var gcCalls = 0
        val checks = LeakCheckQueue(CHECK_DELAY_MS) { gcCalls++ }
        val targets = List(LeakCheckQueue.MAX_PENDING * 2) { Any() }
        val results = ArrayList<LeakResult>()
        val owner = Any()
        try {
            targets.forEach { checks.watch(it, LeakType.CUSTOM, TEST_SCENE, owner, results::add) }
            assertEquals(LeakCheckQueue.MAX_PENDING, checks.pendingCount())
            assertNotSame(Looper.getMainLooper(), checks.handler.looper)
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(CHECK_DELAY_MS))
            assertEquals(1, gcCalls)
            assertEquals(LeakCheckQueue.MAX_PENDING, results.size)
            assertTrue(results.all { it.referenceChain.isEmpty() })
            assertEquals(0, checks.pendingCount())
        } finally { checks.shutdown() }
    }

    /** Small host delays cannot create an unbounded sequence of global GC requests. */
    @Test
    fun `gc requests have a minimum interval across separate bursts`() {
        var gcCalls = 0
        val checks = LeakCheckQueue(1L) { gcCalls++ }
        val first = Any()
        val second = Any()
        try {
            checks.watch(first, LeakType.CUSTOM, TEST_SCENE, this) { }
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(1L))
            assertEquals(1, gcCalls)
            checks.watch(second, LeakType.CUSTOM, TEST_SCENE, this) { }
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(1L))
            assertEquals(1, gcCalls)
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(CHECK_DELAY_MS))
            assertEquals(2, gcCalls)
        } finally { checks.shutdown() }
    }

    /** Cancelling while GC is running prevents the old owner's callback from being delivered. */
    @Test
    fun `cancellation during gc suppresses late results`() {
        val owner = Any()
        val results = ArrayList<LeakResult>()
        lateinit var checks: LeakCheckQueue
        checks = LeakCheckQueue(CHECK_DELAY_MS) { checks.cancel(owner) }
        val target = Any()
        try {
            checks.watch(target, LeakType.CUSTOM, TEST_SCENE, owner, results::add)
            shadowOf(checks.handler.looper).idleFor(Duration.ofMillis(CHECK_DELAY_MS))
            assertTrue(results.isEmpty())
            assertEquals(0, checks.pendingCount())
            checks.shutdown()
            checks.watch(target, LeakType.CUSTOM, TEST_SCENE, owner, results::add)
            assertEquals(0, checks.pendingCount())
        } finally { checks.shutdown() }
    }

    /** Verifies both runnable captures and the actual queue descriptor, without relying on GC luck. */
    private fun assertWeakWatch(checks: LeakCheckQueue, target: Any) {
        val callback = checkNotNull(headCallback(checks.handler))
        assertTrue(callback.javaClass.declaredFields.none { it.isAccessible = true; it.get(callback) === target })
        val entries = checks.javaClass.getDeclaredField("pending").apply { isAccessible = true }.get(checks) as List<*>
        val entry = checkNotNull(entries.single())
        val values = entry.javaClass.declaredFields.map { it.isAccessible = true; it.get(entry) }
        assertTrue(values.none { it === target })
        assertSame(target, values.filterIsInstance<WeakReference<*>>().single().get())
    }

    /** Reads this handler's actual delayed callback from the platform message queue. */
    private fun headCallback(handler: Handler): Runnable? {
        val head = MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
        val next = Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        var message = head.get(handler.looper.queue) as Message?
        while (message != null) {
            if (message.target === handler && message.callback != null) return message.callback
            message = next.get(message) as Message?
        }
        return null
    }

    /** Minimal real View lifecycle for the FragmentManager back-stack regression. */
    class TestFragment : Fragment() {
        /** Provides a host-owned View that is destroyed on replacement. */
        override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?,
            savedInstanceState: android.os.Bundle?): View = View(requireContext())
    }

    companion object {
        /** Fixed shadow-looper advancement. */ private const val CHECK_DELAY_MS = 5_000L
        /** Benign scene descriptor. */ private const val TEST_SCENE = "test"
    }
}
