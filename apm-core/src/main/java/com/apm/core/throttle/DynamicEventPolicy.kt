package com.apm.core.throttle

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Bounded memo of the six interpolated config keys for one (module, name) stream. The same
     * streams arrive on every event, so the cache removes all per-event string interpolation from
     * the dispatcher worker while leaving provider lookups and resolved values unchanged.
     */
    private val eventKeys = ConcurrentHashMap<String, EventPolicyKeys>()

    /** Returns whether one event is retained by deterministic event-id sampling. */
    fun shouldSample(event: ApmEvent): Boolean {
        if (event.severity == ApmSeverity.ERROR || event.severity == ApmSeverity.FATAL) {
            return true
        }
        val keys = keysFor(event.module, event.name)
        val global = readLong(KEY_DEFAULT_SAMPLE_BASIS_POINTS, FULL_BASIS_POINTS)
        val module = readLong(keys.moduleSamplingKey, global)
        val eventSpecific = readLong(keys.eventSamplingKey, module)
            .coerceIn(0L, FULL_BASIS_POINTS)
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
        val keys = keysFor(event.module, event.name)
        val globalCapacity = readLong(
            KEY_DEFAULT_EVENTS_PER_WINDOW,
            defaultEventsPerWindow.toLong()
        )
        val moduleCapacity = readLong(keys.moduleCapacityKey, globalCapacity)
        val eventCapacity = readLong(keys.eventCapacityKey, moduleCapacity)
            .coerceIn(0L, MAX_EVENTS_PER_WINDOW).toInt()

        val globalWindow = readLong(KEY_DEFAULT_WINDOW_MS, defaultWindowMs)
        val moduleWindow = readLong(keys.moduleWindowKey, globalWindow)
        val eventWindow = readLong(keys.eventWindowKey, moduleWindow)
            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
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

    /** Returns the cached interpolated key set for one (module, name) stream. */
    private fun keysFor(module: String, name: String): EventPolicyKeys {
        val cacheKey = StringBuilder(module.length + name.length + 1)
            .append(module)
            .append(KEY_CACHE_SEPARATOR)
            .append(name)
            .toString()
        val cached = eventKeys[cacheKey]
        // Verify the stored stream identity so a pathological separator collision inside
        // module/name identifiers can only lose caching, never apply another stream's keys.
        if (cached != null && cached.module == module && cached.name == name) {
            return cached
        }
        val computed = EventPolicyKeys(
            module = module,
            name = name,
            moduleSamplingKey = "apm.sampling.$module.basis_points",
            eventSamplingKey = "apm.sampling.$module.$name.basis_points",
            moduleCapacityKey = "apm.rate_limit.$module.events_per_window",
            eventCapacityKey = "apm.rate_limit.$module.$name.events_per_window",
            moduleWindowKey = "apm.rate_limit.$module.window_ms",
            eventWindowKey = "apm.rate_limit.$module.$name.window_ms"
        )
        // Cache only while below the capacity bound; decisions stay correct either way.
        if (eventKeys.size < MAX_CACHED_EVENT_KEYS) {
            eventKeys.putIfAbsent(cacheKey, computed)
        }
        return computed
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

        /** Separator guaranteed not to appear in module/name identifiers when composing the cache key. */
        private const val KEY_CACHE_SEPARATOR = '\u0000'

        /** Upper bound for the interpolated-key cache; mirrors the RateLimiter bucket bound. */
        private const val MAX_CACHED_EVENT_KEYS = 256
    }
}

/** Precomputed interpolated dynamic-config keys for one (module, name) event stream. */
private data class EventPolicyKeys(
    /** Module identity retained to reject pathological cache-key separator collisions. */
    val module: String,
    /** Event-name identity retained to reject pathological cache-key separator collisions. */
    val name: String,
    /** Module-level sampling override key. */
    val moduleSamplingKey: String,
    /** Event-level sampling override key. */
    val eventSamplingKey: String,
    /** Module-level rate-limit capacity key. */
    val moduleCapacityKey: String,
    /** Event-level rate-limit capacity key. */
    val eventCapacityKey: String,
    /** Module-level rate-limit window key. */
    val moduleWindowKey: String,
    /** Event-level rate-limit window key. */
    val eventWindowKey: String
)

/** One bounded dynamic token-bucket policy. */
internal data class DynamicRateLimit(
    /** Tokens restored at each window boundary. */
    val eventsPerWindow: Int,
    /** Fixed refill window in milliseconds. */
    val windowMs: Long
)
