package com.apm.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Rule
import org.junit.Test

/** Measures the hot durable-serialization path without network or disk noise. */
class EventCodecBenchmark {
    /** AndroidX benchmark lifecycle and measurement controller. */
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    /** Stable representative event reused across measurement iterations. */
    private val event = ApmEvent(
        module = "benchmark",
        name = "codec",
        kind = ApmEventKind.METRIC,
        severity = ApmSeverity.INFO,
        fields = mapOf(
            "durationMs" to 16L,
            "screen" to "MainActivity",
            "success" to true
        ),
        eventId = "benchmark-event"
    )

    /** Measures one versioned durable event encode. */
    @Test
    fun encodeDurableEvent() = benchmarkRule.measureRepeated {
        ApmEventCodec.encode(event)
    }

    /** Measures one versioned durable event decode. */
    @Test
    fun decodeDurableEvent() {
        val payload = ApmEventCodec.encode(event)
        benchmarkRule.measureRepeated {
            ApmEventCodec.decode(payload)
        }
    }
}
