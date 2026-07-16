package com.apm.core.throttle

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity

/**
 * Resolves verified dynamic sampling and rate-limit policy for one event.
 *
 * Keys use global, module, then event specificity. Every read keeps the previous resolved value as
 * its default, so absent overrides preserve the application-bundled configuration.
 */
internal class DynamicEventPolicy(
    /** Provider whose signed snapshot is exposed through typed getters. */
    private val provider: DynamicConfigProvider,
    /** Recoverable provider failure sink. */
    private val onError: (Exception) -> Unit
) {

    /** Returns whether one event is retained by deterministic event-id sampling. */
    fun shouldSample(event: ApmEvent): Boolean {
        if (event.severity == ApmSeverity.ERROR || event.severity == ApmSeverity.FATAL) {
            return true
        }
        val global = readLong(KEY_DEFAULT_SAMPLE_BASIS_POINTS, FULL_BASIS_POINTS)
        val module = readLong(
            "apm.sampling.${event.module}.basis_points",
            global
        )
        val eventSpecific = readLong(
            "apm.sampling.${event.module}.${event.name}.basis_points",
            module
        ).coerceIn(0L, FULL_BASIS_POINTS)
        if (eventSpecific == FULL_BASIS_POINTS) {
            return true
        }
        if (eventSpecific == 0L) {
            return false
        }
        val bucket = (event.eventId.hashCode() and POSITIVE_HASH_MASK) % FULL_BASIS_POINTS.toInt()
        return bucket < eventSpecific
    }

    /** Resolves bounded rate-limit capacity and window for one event bucket. */
    fun rateLimitFor(
        event: ApmEvent,
        defaultEventsPerWindow: Int,
        defaultWindowMs: Long
    ): DynamicRateLimit {
        val globalCapacity = readLong(
            KEY_DEFAULT_EVENTS_PER_WINDOW,
            defaultEventsPerWindow.toLong()
        )
        val moduleCapacity = readLong(
            "apm.rate_limit.${event.module}.events_per_window",
            globalCapacity
        )
        val eventCapacity = readLong(
            "apm.rate_limit.${event.module}.${event.name}.events_per_window",
            moduleCapacity
        ).coerceIn(0L, MAX_EVENTS_PER_WINDOW).toInt()

        val globalWindow = readLong(KEY_DEFAULT_WINDOW_MS, defaultWindowMs)
        val moduleWindow = readLong(
            "apm.rate_limit.${event.module}.window_ms",
            globalWindow
        )
        val eventWindow = readLong(
            "apm.rate_limit.${event.module}.${event.name}.window_ms",
            moduleWindow
        ).coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        return DynamicRateLimit(eventCapacity, eventWindow)
    }

    /** Reads one long while keeping a custom provider failure outside the host event path. */
    private fun readLong(key: String, defaultValue: Long): Long {
        return try {
            provider.getLongValue(key, defaultValue)
        } catch (error: Exception) {
            onError(error)
            defaultValue
        }
    }

    companion object {
        /** Default event sampling key in ten-thousandths. */
        private const val KEY_DEFAULT_SAMPLE_BASIS_POINTS =
            "apm.sampling.default_basis_points"

        /** Default rate-limit capacity key. */
        private const val KEY_DEFAULT_EVENTS_PER_WINDOW =
            "apm.rate_limit.default_events_per_window"

        /** Default rate-limit window key. */
        private const val KEY_DEFAULT_WINDOW_MS = "apm.rate_limit.default_window_ms"

        /** Sampling denominator. */
        private const val FULL_BASIS_POINTS = 10_000L

        /** Removes the signed bit from a stable JVM string hash. */
        private const val POSITIVE_HASH_MASK = 0x7FFFFFFF

        /** Dynamic rate-limit bounds prevent signed config from creating extreme local state. */
        private const val MAX_EVENTS_PER_WINDOW = 1_000_000L
        private const val MIN_WINDOW_MS = 1_000L
        private const val MAX_WINDOW_MS = 24L * 60L * 60L * 1_000L
    }
}

/** One bounded dynamic token-bucket policy. */
internal data class DynamicRateLimit(
    /** Tokens restored at each window boundary. */
    val eventsPerWindow: Int,
    /** Fixed refill window in milliseconds. */
    val windowMs: Long
)
