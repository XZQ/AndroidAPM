package com.apm.core.diagnostics

import com.apm.core.ApmClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local runtime evidence for host-owned integration surfaces.
 *
 * The registry stores only fixed enums, counters, and timestamps. It never retains URLs, SQL, file paths,
 * callback names, executors, WebViews, or other host values.
 */
internal object HostIntegrationRegistry {

    /** Whether a successfully admitted SDK session currently accepts integration updates. */
    private val sessionActive = AtomicBoolean(false)

    /** Fixed storage indexed by [HostIntegrationPoint.ordinal]. */
    private val entries = Array(HostIntegrationPoint.entries.size) { Entry() }

    /** Starts a fresh SDK session and discards stale evidence from an earlier init/stop cycle. */
    fun beginSession() {
        entries.forEach(Entry::reset)
        sessionActive.set(true)
    }

    /** Ends update acceptance while retaining the last session's observation counts for support inspection. */
    fun endSession() {
        sessionActive.set(false)
        entries.forEach { entry ->
            entry.moduleActive.set(false)
            entry.activeRegistrations.set(0)
        }
    }

    /** Updates whether the owning monitor is currently running. */
    fun setModuleActive(point: HostIntegrationPoint, active: Boolean) {
        if (!sessionActive.get()) {
            return
        }
        val entry = entries[point.ordinal]
        entry.moduleActive.set(active)
        if (!active) {
            entry.activeRegistrations.set(0)
        }
    }

    /** Reconciles an exact current installation or registration count. */
    fun setActiveRegistrations(point: HostIntegrationPoint, count: Int) {
        if (!sessionActive.get()) {
            return
        }
        entries[point.ordinal].activeRegistrations.set(count.coerceAtLeast(0))
    }

    /** Records one value-free signal proving that a host integration entry point executed. */
    fun recordObservation(point: HostIntegrationPoint) {
        if (!sessionActive.get()) {
            return
        }
        val entry = entries[point.ordinal]
        entry.observedSignals.getAndUpdate { count ->
            if (count == Long.MAX_VALUE) Long.MAX_VALUE else count + 1L
        }
        entry.lastObservedAtMs.set(ApmClock.wallTimeMillis())
    }

    /** Returns a stable immutable snapshot without filesystem work. */
    fun snapshot(): HostIntegrationSnapshot {
        return HostIntegrationSnapshot(
            capturedAtMs = ApmClock.wallTimeMillis(),
            integrations = HostIntegrationPoint.entries.map { point ->
                val entry = entries[point.ordinal]
                val moduleActive = entry.moduleActive.get()
                val activeRegistrations = entry.activeRegistrations.get()
                val observedSignals = entry.observedSignals.get()
                HostIntegrationStatus(
                    point = point,
                    moduleActive = moduleActive,
                    activeRegistrations = activeRegistrations,
                    observedSignals = observedSignals,
                    lastObservedAtMs = entry.lastObservedAtMs.get().takeIf { it > 0L },
                    state = deriveState(moduleActive, activeRegistrations, observedSignals)
                )
            }
        )
    }

    /** Derives a reader-facing state without collapsing registration and execution evidence. */
    private fun deriveState(
        moduleActive: Boolean,
        activeRegistrations: Int,
        observedSignals: Long
    ): HostIntegrationState {
        if (!moduleActive) {
            return HostIntegrationState.MODULE_INACTIVE
        }
        return when {
            activeRegistrations > 0 && observedSignals > 0L ->
                HostIntegrationState.REGISTRATION_ACTIVE_AND_OBSERVED
            activeRegistrations > 0 -> HostIntegrationState.REGISTRATION_ACTIVE
            observedSignals > 0L -> HostIntegrationState.OBSERVED
            else -> HostIntegrationState.NO_RUNTIME_EVIDENCE
        }
    }

    /** Mutable atomic state for one fixed integration point. */
    private class Entry {
        /** Whether the owning feature module is running. */
        val moduleActive = AtomicBoolean(false)
        /** Exact current installation or registration count. */
        val activeRegistrations = AtomicInteger(0)
        /** Saturating count of observed entry-point signals. */
        val observedSignals = AtomicLong(0L)
        /** Latest observation wall time; zero means absent. */
        val lastObservedAtMs = AtomicLong(0L)

        /** Clears all fields for a new SDK session. */
        fun reset() {
            moduleActive.set(false)
            activeRegistrations.set(0)
            observedSignals.set(0L)
            lastObservedAtMs.set(0L)
        }
    }
}
