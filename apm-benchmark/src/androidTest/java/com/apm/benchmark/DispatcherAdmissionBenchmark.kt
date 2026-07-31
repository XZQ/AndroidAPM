package com.apm.benchmark

import android.app.Application
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import com.apm.core.Apm
import com.apm.core.ApmConfig
import com.apm.core.StorageType
import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import com.apm.uploader.ApmUploader
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Measures dispatcher producer admission independently of storage and transport latency.
 *
 * A first event blocks the dispatcher worker inside a test uploader. This keeps subsequent work in
 * the real bounded queue while each measured iteration creates and tears down a fresh SDK runtime
 * with measurement disabled.
 */
class DispatcherAdmissionBenchmark {
    /** AndroidX benchmark lifecycle and measurement controller. */
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    /** Target application used by the public SDK initialization path. */
    private val application: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    /** Representative immutable input that the public API snapshots on every occurrence. */
    private val fields = mapOf(
        "durationMs" to 16L,
        "screen" to "MainActivity",
        "success" to true
    )

    /** Prebuilt identities keep benchmark allocation counts scoped to the SDK caller path. */
    private val acceptedNames = List(ACCEPTED_BATCH_SIZE) { index -> "accepted-$index" }

    /** Restores the process-global SDK state even when a benchmark assertion fails. */
    @After
    fun cleanUp() {
        stopWithoutDrain()
        Apm.grantCollectionConsent()
    }

    /** Measures 32 accepted caller-side emits while the worker is isolated from the sample. */
    @Test
    fun emitAcceptedBatch() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled { startBlockedRuntime() }
        repeat(ACCEPTED_BATCH_SIZE) { index ->
            Apm.emit(
                module = "benchmark",
                name = acceptedNames[index],
                fields = fields
            )
        }
        runWithMeasurementDisabled { stopWithoutDrain() }
    }

    /** Measures emergency HIGH admission and one LOW eviction at the full 2,048-event capacity. */
    @Test
    fun emitHighPriorityIntoFullQueue() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            startBlockedRuntime()
            repeat(DISPATCHER_QUEUE_CAPACITY) { index ->
                Apm.emit(
                    module = "benchmark",
                    name = "queued-$index",
                    priority = ApmPriority.LOW
                )
            }
        }
        Apm.emit(
            module = "benchmark",
            name = "high-priority",
            priority = ApmPriority.HIGH,
            fields = fields
        )
        runWithMeasurementDisabled { stopWithoutDrain() }
    }

    /** Starts a public SDK runtime whose dispatcher worker is parked in the uploader. */
    private fun startBlockedRuntime() {
        stopWithoutDrain()
        Apm.grantCollectionConsent()
        val uploader = BlockingUploader()
        Apm.init(
            application,
            ApmConfig(
                uploader = uploader,
                storageType = StorageType.FILE,
                rateLimitEventsPerWindow = 0,
                enableRetry = false,
                enableAggregation = false,
                enableAutoThrottle = false,
                enableDispatcherModuleIsolation = false
            )
        )
        Apm.emit(module = "benchmark", name = "block-worker")
        check(uploader.awaitWorker()) {
            "Dispatcher worker did not reach the benchmark uploader"
        }
    }

    /** Discards queued benchmark telemetry instead of timing drain, persistence, or upload work. */
    private fun stopWithoutDrain() {
        if (Apm.isInitialized()) {
            Apm.revokeCollectionConsent(application)
        }
    }

    /** Interruptible uploader used only to park the dispatcher worker. */
    private class BlockingUploader : ApmUploader {
        /** Signals that the worker completed all pipeline stages before upload. */
        private val workerEntered = CountDownLatch(1)

        /** Keeps the worker outside the producer-side measurement. */
        private val releaseWorker = CountDownLatch(1)

        /** Parks the worker until revocation interrupts it or cleanup releases it. */
        override fun upload(event: ApmEvent): Boolean {
            workerEntered.countDown()
            return try {
                releaseWorker.await(WORKER_BLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        /** Waits for a deterministic empty-queue starting point. */
        fun awaitWorker(): Boolean =
            workerEntered.await(WORKER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        /** Ensures fallback shutdown cannot retain the instrumentation process. */
        override fun shutdown() {
            releaseWorker.countDown()
        }
    }

    /** Benchmark constants. */
    private companion object {
        /** Matches the production dispatcher count capacity. */
        private const val DISPATCHER_QUEUE_CAPACITY = 2_048

        /** Matches the production maximum worker drain batch. */
        private const val ACCEPTED_BATCH_SIZE = 32

        /** Hard setup failure bound, excluded from measurement. */
        private const val WORKER_START_TIMEOUT_SECONDS = 5L

        /** Defensive worker-park bound; normal cleanup interrupts immediately. */
        private const val WORKER_BLOCK_TIMEOUT_SECONDS = 30L
    }
}
