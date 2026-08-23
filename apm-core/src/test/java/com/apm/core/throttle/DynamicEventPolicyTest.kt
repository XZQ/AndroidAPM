package com.apm.core.throttle

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dynamic sampling and rate-limit precedence tests. */
class DynamicEventPolicyTest {

    /** Event-specific sampling overrides module and global values. */
    @Test
    fun `event sampling override has highest precedence`() {
        val provider = MapProvider(
            mapOf(
                "apm.sampling.default_basis_points" to 0L,
                "apm.sampling.network.basis_points" to 0L,
                "apm.sampling.network.request.basis_points" to 10_000L
            )
        )
        val policy = DynamicEventPolicy(provider) { error -> throw error }

        assertTrue(policy.shouldSample(event()))
    }

    /** Non-critical events can be fully sampled out while error telemetry remains protected. */
    @Test
    fun `critical severity bypasses zero sampling`() {
        val policy = DynamicEventPolicy(
            MapProvider(mapOf("apm.sampling.default_basis_points" to 0L))
        ) { error -> throw error }

        assertFalse(policy.shouldSample(event()))
        assertTrue(policy.shouldSample(event(severity = ApmSeverity.ERROR)))
        assertTrue(policy.shouldSample(event(severity = ApmSeverity.FATAL)))
    }

    /** Event-specific rate settings override module and global defaults and remain bounded. */
    @Test
    fun `dynamic rate limit uses most specific bounded values`() {
        val provider = MapProvider(
            mapOf(
                "apm.rate_limit.default_events_per_window" to 20L,
                "apm.rate_limit.network.events_per_window" to 30L,
                "apm.rate_limit.network.request.events_per_window" to 40L,
                "apm.rate_limit.default_window_ms" to 10_000L,
                "apm.rate_limit.network.request.window_ms" to 500L
            )
        )
        val policy = DynamicEventPolicy(provider) { error -> throw error }

        val limit = policy.rateLimitFor(event(), 10, 60_000L)

        assertEquals(40, limit.eventsPerWindow)
        assertEquals(1_000L, limit.windowMs)
    }

    /** Oversized stream identifiers are resolved normally without being retained by the memo. */
    @Test
    fun `oversized stream identifiers bypass interpolated key cache`() {
        val policy = DynamicEventPolicy(MapProvider(emptyMap())) { error -> throw error }

        assertTrue(policy.shouldSample(event(module = "m".repeat(1_024))))

        val cacheField = DynamicEventPolicy::class.java.getDeclaredField("eventKeys")
        cacheField.isAccessible = true
        val cache = cacheField.get(policy) as Map<*, *>
        assertTrue(cache.isEmpty())

        assertTrue(policy.shouldSample(event()))
        assertEquals(1, cache.size)
    }

    /** Creates one stable event for key resolution. */
    private fun event(
        severity: ApmSeverity = ApmSeverity.INFO,
        module: String = "network",
        name: String = "request",
    ): ApmEvent = ApmEvent(
        module = module,
        name = name,
        severity = severity,
        eventId = "event-policy-test"
    )

    /** Map-backed typed provider used by policy tests. */
    private class MapProvider(
        /** Long values keyed by dynamic-config path. */
        private val values: Map<String, Long>
    ) : DynamicConfigProvider {
        /** Returns the caller default for unrelated boolean keys. */
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue

        /** Returns a configured long or the caller default. */
        override fun getLongValue(key: String, defaultValue: Long): Long = values[key] ?: defaultValue

        /** Returns the caller default for unrelated float keys. */
        override fun getFloatValue(key: String, defaultValue: Float): Float = defaultValue

        /** Returns the caller default for unrelated string keys. */
        override fun getString(key: String, defaultValue: String): String = defaultValue
    }
}
