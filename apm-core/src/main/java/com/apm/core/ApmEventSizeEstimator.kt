package com.apm.core

import com.apm.model.ApmEvent
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Estimates retained event payload bytes without serializing on a host or main thread.
 *
 * The estimate deliberately counts UTF-16 character storage, map entry overhead, and supported
 * scalar storage conservatively. It is an admission-control weight rather than an object-graph
 * profiler: arbitrary host objects receive a fixed defensive charge and are still bounded by the
 * exact durable/IPC codec limits later in the pipeline.
 */
internal object ApmEventSizeEstimator {
    /** Returns a conservative process-local retention weight for a complete event. */
    fun estimate(event: ApmEvent): Long {
        return estimate(
            module = event.module,
            name = event.name,
            processName = event.processName,
            threadName = event.threadName,
            scene = event.scene,
            fields = event.fields,
            primaryContext = event.globalContext,
            secondaryContext = emptyMap(),
            extras = event.extras
        )
    }

    /**
     * Estimates a lazy event before its default and business contexts are merged by the worker.
     */
    fun estimate(
        module: String,
        name: String,
        processName: String,
        threadName: String,
        scene: String?,
        fields: Map<String, Any?>,
        primaryContext: Map<String, String>,
        secondaryContext: Map<String, String>,
        extras: Map<String, String>
    ): Long {
        var total = MIN_EVENT_ESTIMATE_BYTES
        total = add(total, stringBytes(module))
        total = add(total, stringBytes(name))
        total = add(total, stringBytes(processName))
        total = add(total, stringBytes(threadName))
        if (scene != null) {
            total = add(total, stringBytes(scene))
        }
        total = add(total, typedMapBytes(fields))
        total = add(total, stringMapBytes(primaryContext))
        total = add(total, stringMapBytes(secondaryContext))
        total = add(total, stringMapBytes(extras))
        return total
    }

    /**
     * Precomputes the per-process constant portion of [estimate]: base event overhead, the fixed
     * process name, and the frozen init-time default context. Both inputs are stable for the
     * lifetime of one [com.apm.core.Apm] runtime, so the emit hot path can add this cached value
     * instead of re-walking the default-context map on every call.
     */
    fun constantRetentionBytes(
        processName: String,
        defaultContext: Map<String, String>
    ): Long {
        var total = MIN_EVENT_ESTIMATE_BYTES
        total = add(total, stringBytes(processName))
        total = add(total, stringMapBytes(defaultContext))
        return total
    }

    /**
     * Estimates a lazy event from a precomputed [constantRetentionBytes] base plus the per-emit
     * variable parts. The numeric result is identical to the full [estimate] overload.
     */
    fun estimate(
        module: String,
        name: String,
        threadName: String,
        scene: String?,
        fields: Map<String, Any?>,
        constantBaseBytes: Long,
        secondaryContext: Map<String, String>,
        extras: Map<String, String>
    ): Long {
        var total = constantBaseBytes
        total = add(total, stringBytes(module))
        total = add(total, stringBytes(name))
        total = add(total, stringBytes(threadName))
        if (scene != null) {
            total = add(total, stringBytes(scene))
        }
        total = add(total, typedMapBytes(fields))
        total = add(total, stringMapBytes(secondaryContext))
        total = add(total, stringMapBytes(extras))
        return total
    }

    /** Estimates one typed field map without calling arbitrary host `toString()` methods. */
    private fun typedMapBytes(values: Map<String, Any?>): Long {
        var total = MAP_HEADER_BYTES
        for ((key, value) in values) {
            total = add(total, MAP_ENTRY_BYTES)
            total = add(total, stringBytes(key))
            total = add(total, typedValueBytes(value))
        }
        return total
    }

    /** Estimates one string map retained by an event. */
    private fun stringMapBytes(values: Map<String, String>): Long {
        var total = MAP_HEADER_BYTES
        for ((key, value) in values) {
            total = add(total, MAP_ENTRY_BYTES)
            total = add(total, stringBytes(key))
            total = add(total, stringBytes(value))
        }
        return total
    }

    /** Estimates one supported scalar or applies a defensive charge to an arbitrary object. */
    private fun typedValueBytes(value: Any?): Long {
        return when (value) {
            null -> NULL_VALUE_BYTES
            is String -> stringBytes(value)
            is Boolean, is Byte, is Short, is Int, is Float, is Char -> SMALL_SCALAR_BYTES
            is Long, is Double -> LARGE_SCALAR_BYTES
            is BigInteger -> BIG_NUMBER_HEADER_BYTES + ((value.bitLength().coerceAtLeast(1) + 7L) / 8L)
            is BigDecimal -> BIG_NUMBER_HEADER_BYTES +
                ((value.unscaledValue().bitLength().coerceAtLeast(1) + 7L) / 8L)
            else -> UNKNOWN_OBJECT_BYTES
        }
    }

    /** Estimates one JVM/ART String object and its UTF-16 backing storage. */
    private fun stringBytes(value: String): Long {
        return STRING_HEADER_BYTES + value.length.toLong() * UTF16_BYTES_PER_CHARACTER
    }

    /** Saturating addition prevents attacker-controlled collections from overflowing admission. */
    private fun add(current: Long, delta: Long): Long {
        if (delta >= Long.MAX_VALUE - current) {
            return Long.MAX_VALUE
        }
        return current + delta
    }

    /** Minimum retained object/map/event overhead, also bounding the maximum task count by bytes. */
    private const val MIN_EVENT_ESTIMATE_BYTES = 256L

    /** Approximate ART/JVM String object and backing-array headers. */
    private const val STRING_HEADER_BYTES = 40L

    /** Conservative character width independent of compact-string implementation details. */
    private const val UTF16_BYTES_PER_CHARACTER = 2L

    /** Approximate empty map object overhead. */
    private const val MAP_HEADER_BYTES = 48L

    /** Approximate map node plus key/value reference overhead. */
    private const val MAP_ENTRY_BYTES = 64L

    /** Retention charge for a null field value. */
    private const val NULL_VALUE_BYTES = 8L

    /** Retention charge for a boxed scalar no larger than 32 bits. */
    private const val SMALL_SCALAR_BYTES = 24L

    /** Retention charge for a boxed 64-bit scalar. */
    private const val LARGE_SCALAR_BYTES = 32L

    /** Object overhead charged in addition to arbitrary-precision magnitude bytes. */
    private const val BIG_NUMBER_HEADER_BYTES = 64L

    /** Defensive charge for a host object whose transitive graph cannot be inspected safely. */
    private const val UNKNOWN_OBJECT_BYTES = 256L
}
