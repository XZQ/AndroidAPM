package com.apm.core

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Supplies immutable business-context snapshots without changing event construction semantics.
 *
 * Synchronous mode captures the provider on the emit caller for exact occurrence-time values.
 * Asynchronous mode invokes the provider only on an SDK executor and exposes its last good result
 * through an atomic reference, so emit performs an O(1) read and never waits for host code.
 */
internal class BizContextSnapshotSource(
    /** Host-owned source of dynamic business context. */
    private val provider: BizContextProvider,
    /** Selected caller-snapshot or asynchronous-cache behavior. */
    private val mode: BizContextCaptureMode,
    /** Requested asynchronous refresh interval before runtime bounds are applied. */
    refreshIntervalMs: Long,
    /** Recoverable host-provider or scheduling failure sink. */
    private val onError: (Exception) -> Unit,
    /** Executor factory kept injectable for deterministic lifecycle tests. */
    private val executorFactory: () -> ScheduledExecutorService = {
        ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME)
    }
) {
    /** Last successfully captured immutable context used by asynchronous mode. */
    private val cachedSnapshot = AtomicReference<Map<String, String>>(emptyMap())

    /** Coalesces explicit refresh requests so the single worker cannot accumulate an unbounded queue. */
    private val refreshPending = AtomicBoolean(false)

    /** Runtime-bounded asynchronous refresh interval. */
    private val effectiveRefreshIntervalMs = refreshIntervalMs.coerceIn(
        MIN_REFRESH_INTERVAL_MS,
        MAX_REFRESH_INTERVAL_MS
    )

    /** Asynchronous refresh executor; null before start, in synchronous mode, and after stop. */
    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Starts periodic background refresh when [mode] is [BizContextCaptureMode.ASYNC_CACHED]. */
    @Synchronized
    fun start() {
        if (mode != BizContextCaptureMode.ASYNC_CACHED || executor != null) {
            return
        }
        val createdExecutor = executorFactory()
        try {
            // Initial delay is zero so the first cache fill is asynchronous but starts immediately.
            createdExecutor.scheduleWithFixedDelay(
                ::refreshSafely,
                0L,
                effectiveRefreshIntervalMs,
                TimeUnit.MILLISECONDS
            )
            executor = createdExecutor
        } catch (error: Exception) {
            // A partially started scheduler remains owned here and must not leak on init failure.
            createdExecutor.shutdownNow()
            throw error
        }
    }

    /** Returns one immutable snapshot according to the configured capture mode. */
    fun capture(): Map<String, String> {
        return if (mode == BizContextCaptureMode.SYNCHRONOUS) {
            captureBizContextSafely(provider, onError)
        } else {
            cachedSnapshot.get()
        }
    }

    /**
     * Requests one coalesced asynchronous refresh without invoking host code on the caller.
     *
     * @return true when a new refresh task was accepted
     */
    fun requestRefresh(): Boolean {
        if (mode != BizContextCaptureMode.ASYNC_CACHED || !refreshPending.compareAndSet(false, true)) {
            return false
        }
        val currentExecutor = executor
        if (currentExecutor == null) {
            refreshPending.set(false)
            return false
        }
        return try {
            currentExecutor.execute {
                try {
                    refreshSafely()
                } finally {
                    // One pending request is released only after its provider call finishes.
                    refreshPending.set(false)
                }
            }
            true
        } catch (error: Exception) {
            refreshPending.set(false)
            onError(error)
            false
        }
    }

    /** Stops asynchronous refresh and releases its executor; synchronous mode is a no-op. */
    @Synchronized
    fun stop() {
        val currentExecutor = executor
        executor = null
        refreshPending.set(false)
        currentExecutor?.shutdownNow()
    }

    /** Executes one refresh synchronously for deterministic unit tests. */
    internal fun refreshNowForTest() {
        refreshSafely()
    }

    /** Publishes one immutable snapshot while retaining the last good value after recoverable failure. */
    private fun refreshSafely() {
        try {
            cachedSnapshot.set(provider.currentContext().toMap())
        } catch (error: Exception) {
            // A transient provider failure must not erase previously captured event context.
            onError(error)
        }
    }

    companion object {
        /** SDK executor thread name suffix. */
        private const val THREAD_NAME = "biz-context"

        /** Fastest supported refresh cadence, preventing a host provider busy loop. */
        private const val MIN_REFRESH_INTERVAL_MS = 100L

        /** Slowest supported cadence, preventing overflow and effectively disabled scheduling. */
        private const val MAX_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    }
}
