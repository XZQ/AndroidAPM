package com.apm.memory.leak

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.apm.core.Apm
import java.lang.ref.WeakReference

/** One bounded, coalesced background GC/check queue shared by a memory-module session. */
internal class LeakCheckQueue(
    /** Grace period after final destruction. */
    private val delayMs: Long = DEFAULT_DELAY_MS,
    /** Injectable GC boundary for deterministic lifecycle tests. */
    private val collectGarbage: () -> Unit = {
        Runtime.getRuntime().gc()
        System.runFinalization()
    }
) {
    /** Serializes owner cancellation and delivery, without holding a lock during GC. */
    private val lock = Any()
    /** At most one delayed runnable is posted to this background looper. */
    private val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    /** Background handler, exposed internally for deterministic queue verification. */
    internal val handler = Handler(thread.looper)
    /** Weak targets and scalar metadata only; no delayed lambda captures a target. */
    private val pending = ArrayList<Watch>()
    /** Permanent session closure; an old callback cannot be admitted after a restart. */
    private var closed = false
    /** Lower bound for the next process-wide GC request, even under continuous lifecycle churn. */
    private var nextGcAllowedAtMs = 0L
    /** Reused callback captures this queue, whose targets are all weak. */
    private val checkTask = Runnable(::checkPending)

    /** Tracks one destroyed object while keeping the queue bounded under lifecycle churn. */
    fun watch(target: Any, type: LeakType, scene: String, owner: Any, onLeak: (LeakResult) -> Unit) {
        val now = SystemClock.uptimeMillis()
        val deadline = now + delayMs.coerceIn(1L, Long.MAX_VALUE - now)
        val watch = Watch(WeakReference(target), target.javaClass.name, type, scene, owner, deadline, onLeak)
        synchronized(lock) {
            if (closed) return
            if (pending.size >= MAX_PENDING) pending.removeAt(0)
            pending += watch
            scheduleLocked()
        }
    }

    /** Cancels queued and in-progress checks for one detector before it unregisters. */
    fun cancel(owner: Any) = synchronized(lock) {
        pending.removeAll { it.owner === owner }
        scheduleLocked()
    }

    /** Drops references and callbacks immediately, then terminates the shared worker. */
    fun shutdown() = synchronized(lock) {
        closed = true
        pending.clear()
        handler.removeCallbacksAndMessages(null)
        thread.quit()
        Unit
    }

    /** Returns the bounded watch count for lifecycle/cancellation tests. */
    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Posts only the earliest deadline; all objects due together share one GC request. */
    private fun scheduleLocked() {
        handler.removeCallbacks(checkTask)
        if (!closed && pending.isNotEmpty()) {
            val deadline = maxOf(pending.minOf(Watch::deadlineMs), nextGcAllowedAtMs)
            handler.postAtTime(checkTask, deadline)
        }
    }

    /** Performs GC off the main looper, then validates owner/session liveness before reporting. */
    private fun checkPending() {
        val due = synchronized(lock) {
            if (closed) return
            val now = SystemClock.uptimeMillis()
            pending.filter { it.deadlineMs <= now }.also {
                if (it.isNotEmpty()) nextGcAllowedAtMs = now + MIN_GC_INTERVAL_MS
            }
        }
        try {
            if (due.isNotEmpty()) collectGarbage()
            for (watch in due) {
                synchronized(lock) {
                    // Unregister/stop/eviction may have removed the watch while GC ran.
                    if (closed || !pending.remove(watch)) return@synchronized
                    val retained = watch.reference.get() ?: return@synchronized
                    try {
                        watch.onLeak(LeakResult(
                            leakClass = watch.className,
                            type = watch.type,
                            scene = watch.scene,
                            suspectFields = inspectOutgoingFields(retained)
                        ))
                    } catch (error: Exception) {
                        Apm.recordInternalError(ERROR_REPORT, error)
                    }
                }
            }
        } finally {
            synchronized(lock) { scheduleLocked() }
        }
    }

    /** Bounded outgoing-field hints, never described as an incoming path from a GC root. */
    private fun inspectOutgoingFields(target: Any): List<String> {
        val hints = ArrayList<String>()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java && hints.size < MAX_FIELD_HINTS) {
            for (field in type.declaredFields) {
                if (hints.size >= MAX_FIELD_HINTS) break
                try {
                    field.isAccessible = true
                    val value = field.get(target)
                    if (value !== target && (value is android.content.Context ||
                        value is android.view.View || value is Handler || value is Runnable || value is Thread)) {
                        hints += "${type.simpleName}.${field.name} -> ${value.javaClass.simpleName}"
                    }
                } catch (_: Exception) {
                    // Restricted reflection only removes an optional diagnostic hint.
                }
            }
            type = type.superclass
        }
        return hints
    }

    /** Immutable target descriptor; the sole reference to the observed object is weak. */
    private class Watch(
        /** Weak observed object. */ val reference: WeakReference<Any>,
        /** Class captured at destruction time. */ val className: String,
        /** Lifecycle kind. */ val type: LeakType,
        /** Scene captured without retaining its Activity. */ val scene: String,
        /** Opaque cancellation token, never the observed object. */ val owner: Any,
        /** Monotonic deadline. */ val deadlineMs: Long,
        /** Session-scoped result sink. */ val onLeak: (LeakResult) -> Unit
    )

    companion object {
        /** Default post-destruction grace period. */ private const val DEFAULT_DELAY_MS = 5_000L
        /** GC is process-wide and may pause mutators, so requests are rate bounded as well. */
        private const val MIN_GC_INTERVAL_MS = 5_000L
        /** Fixed retained-work bound. */ internal const val MAX_PENDING = 128
        /** Bounded optional reflection output. */ private const val MAX_FIELD_HINTS = 32
        /** One background worker per module session. */ private const val THREAD_NAME = "apm-leak-check"
        /** Payload-free callback failure tag. */ private const val ERROR_REPORT = "memory_leak_report"
    }
}
