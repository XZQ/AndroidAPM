package com.apm.model

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Generates opaque process-unique event identities without per-event secure randomness. */
internal object ApmEventIdGenerator {
    /** Random prefix created once for the current producing process. */
    private val processPrefix = UUID.randomUUID().toString()
    /** Monotonic suffix preventing collisions within [processPrefix]. */
    private val sequence = AtomicLong(0L)

    /** Returns one opaque event identity. */
    fun next(): String = "$processPrefix-${sequence.incrementAndGet().toString(EVENT_ID_RADIX)}"

    /** Compact radix used for the monotonic suffix. */
    private const val EVENT_ID_RADIX = 16
}
