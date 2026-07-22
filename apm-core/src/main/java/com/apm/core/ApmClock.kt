package com.apm.core

import java.util.concurrent.TimeUnit

/**
 * Separates human-readable wall time from monotonic process-local interval measurement.
 *
 * Wall time may jump when the user, network, or operating system adjusts the clock and must
 * therefore never be subtracted to measure latency, cooldowns, or rolling windows.
 */
object ApmClock {
    /** Returns the current Unix epoch timestamp in milliseconds. */
    @JvmStatic
    fun wallTimeMillis(): Long = System.currentTimeMillis()

    /** Returns an arbitrary-origin monotonic timestamp in nanoseconds for elapsed-time work. */
    @JvmStatic
    fun monotonicTimeNanos(): Long = System.nanoTime()

    /** Returns an arbitrary-origin monotonic timestamp in milliseconds for interval windows. */
    @JvmStatic
    fun monotonicTimeMillis(): Long = TimeUnit.NANOSECONDS.toMillis(monotonicTimeNanos())

    /**
     * Returns a non-negative elapsed duration from a timestamp produced by [monotonicTimeMillis].
     *
     * The defensive clamp prevents an invalid injected/test start value from leaking a negative
     * duration into telemetry; production values from the same monotonic source do not regress.
     */
    @JvmStatic
    fun elapsedMillisSince(startedAtMs: Long): Long {
        return nonNegativeMonotonicDurationMillis(startedAtMs, monotonicTimeMillis())
    }
}

/** Calculates a non-negative interval from two timestamps on the same monotonic time base. */
internal fun nonNegativeMonotonicDurationMillis(startedAtMs: Long, endedAtMs: Long): Long {
    return (endedAtMs - startedAtMs).coerceAtLeast(0L)
}
