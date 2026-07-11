package com.apm.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import com.apm.model.ApmEvent
import com.apm.storage.EventDbHelper
import com.apm.storage.SQLiteEventStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Measures the production SQLite durable-outbox batch append path. */
class SQLiteOutboxBenchmark {
    /** AndroidX benchmark lifecycle and measurement controller. */
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    /** Store created in the benchmark target context. */
    private lateinit var store: SQLiteEventStore

    /** Database name unique to this instrumentation process. */
    private val databaseName = "apm-benchmark.db"

    /** Representative dispatcher drain batch. */
    private val events = (0 until BATCH_SIZE).map { index ->
        ApmEvent(
            module = "benchmark",
            name = "sqlite-$index",
            fields = mapOf("index" to index, "payload" to PAYLOAD),
            eventId = "benchmark-sqlite-$index"
        )
    }

    /** Creates a fresh production store before each benchmark method. */
    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        store = SQLiteEventStore(EventDbHelper(context, databaseName))
    }

    /** Closes and removes benchmark state after measurement. */
    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store.close()
        context.deleteDatabase(databaseName)
    }

    /** Measures one 32-event SQLite transaction, excluding reset work. */
    @Test
    fun appendDispatcherBatch() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled { store.clear() }
        store.appendBatch(events)
    }

    /** Static benchmark constants. */
    private companion object {
        /** Matches the dispatcher maximum drain batch. */
        private const val BATCH_SIZE = 32
        /** Representative field payload that exercises durable encoding. */
        private const val PAYLOAD = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
