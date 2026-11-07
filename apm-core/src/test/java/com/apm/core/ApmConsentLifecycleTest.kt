package com.apm.core

import android.app.Application
import com.apm.core.diagnostics.DiagnosticsConfig
import com.apm.core.privacy.SanitizationRule
import com.apm.storage.EventDbHelper
import com.apm.storage.SQLiteEventStore
import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import com.apm.core.throttle.ManagedDynamicConfigProvider
import com.apm.model.ApmEvent
import com.apm.uploader.ApmUploader
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** End-to-end process-local consent lifecycle tests for strict production collection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApmConsentLifecycleTest {
    /** Robolectric application used to create the real SQLite outbox. */
    private lateinit var application: Application

    /** Restores a granted, stopped process state before each test. */
    @Before
    fun setUp() {
        Apm.stop()
        Apm.grantCollectionConsent()
        application = RuntimeEnvironment.getApplication()
    }

    /** Stops SDK threads and prevents sticky consent state from leaking to other test classes. */
    @After
    fun tearDown() {
        Apm.stop()
        Apm.grantCollectionConsent()
    }

    /** Revocation stops runtime, clears durable telemetry, and blocks re-init until explicit grant. */
    @Test
    fun `revocation clears outbox and requires a new grant`() {
        val config = strictConfig()
        Apm.init(application, config)
        assertTrue(
            Apm.emitCriticalSync(
                module = "privacy",
                name = "pending_before_revoke",
                fields = mapOf("account" to "sensitive")
            )
        )

        val result = Apm.revokeCollectionConsent()

        assertTrue(result.wasInitialized)
        assertTrue(result.storageCleared)
        assertTrue(result.uploadWorkerStopped == true)
        assertTrue(result.ipcFilesCleared)
        assertNotNull(result.clearedStoredEventCount)
        assertTrue((result.clearedStoredEventCount ?: 0) >= 1)
        assertTrue(Apm.isCollectionConsentRevoked())
        assertFalse(Apm.isInitialized())
        assertThrows(IllegalStateException::class.java) {
            Apm.init(application, config)
        }

        Apm.grantCollectionConsent()
        Apm.init(application, config)
        assertTrue(Apm.isInitialized())
    }

    /** Application overload clears durable rows after a prior runtime has already stopped. */
    @Test
    fun `cold start revocation clears dormant outbox`() {
        val config = strictConfig()
        Apm.init(application, config)
        assertTrue(
            Apm.emitCriticalSync(
                module = "privacy",
                name = "pending_before_stop",
                fields = mapOf("account" to "sensitive")
            )
        )
        Apm.stop()

        val result = Apm.revokeCollectionConsent(application)

        assertFalse(result.wasInitialized)
        assertTrue(result.storageCleared)
        assertTrue(result.ipcFilesCleared)
        assertNotNull(result.clearedStoredEventCount)
        assertTrue((result.clearedStoredEventCount ?: 0) >= 1)
        assertThrows(IllegalStateException::class.java) {
            Apm.init(application, config)
        }
    }

    /** No-argument revocation reports inability to erase dormant artifacts without an Application. */
    @Test
    fun `dormant no argument revocation is fail closed and reports unresolved storage`() {
        val result = Apm.revokeCollectionConsent()

        assertFalse(result.wasInitialized)
        assertFalse(result.storageCleared)
        assertFalse(result.ipcFilesCleared)
        assertTrue(Apm.isCollectionConsentRevoked())
    }

    /** New init cannot reuse registered module instances while their prior onStop is executing. */
    @Test
    fun `stop excludes init until module cleanup completes`() = verifyCleanupExclusion(revoke = false)

    /** A new grant cannot let old privacy erasure delete a newly initialized runtime's data. */
    @Test
    fun `revocation excludes init even after a new grant during cleanup`() = verifyCleanupExclusion(revoke = true)

    /** A second cleanup closes old admission but returns unproven rather than erasing concurrently. */
    @Test
    fun `revocation during stop is unproven and permits a later dormant retry`() =
        verifyCleanupExclusion(revoke = false, concurrentRevoke = true)

    /** An old provider callback must never read or reconcile the newly installed provider's state. */
    @Test
    fun `delayed dynamic config callback cannot enter the next session`() {
        val oldProvider = RetainingManagedProvider()
        Apm.register(BlockingLifecycleModule())
        Apm.init(application, strictConfig().copy(dynamicConfigProvider = oldProvider))
        Apm.stop()
        val newProvider = RetainingManagedProvider()
        Apm.init(application, strictConfig().copy(dynamicConfigProvider = newProvider))
        val readsBefore = newProvider.booleanReads.get()

        oldProvider.callback?.invoke()

        assertEquals(readsBefore, newProvider.booleanReads.get())
        assertTrue(Apm.isInitialized())
    }

    /** The SDK health timer cannot hold the lifecycle lock while executing a host provider. */
    @Test
    fun `blocked health business context cannot block consent revocation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Apm.init(application, strictConfig().copy(
            enableSelfMonitoring = true,
            enableAutoThrottle = false,
            selfMonitorIntervalMs = HEALTH_INTERVAL_MS,
            bizContextProvider = BizContextProvider {
                entered.countDown()
                while (release.count != 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Simulate host code which outlives executor shutdown.
                    }
                }
                mapOf(TEST_FIELD to BLOCKING_VALUE)
            }
        ))
        val completed = CountDownLatch(1)
        val result = AtomicReference<ConsentRevocationResult>()
        val revoker = Thread {
            try {
                result.set(Apm.revokeCollectionConsent(application))
            } finally {
                completed.countDown()
            }
        }.apply { isDaemon = true }
        try {
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            revoker.start()
            assertTrue("Revocation must not wait for a host context callback", completed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(result.get().storageCleared)
            Apm.grantCollectionConsent()
            Apm.init(application, strictConfig())
        } finally {
            release.countDown()
            revoker.join(JOIN_MS)
        }
        assertFalse(revoker.isAlive)
    }

    /** The final module gate executes after the provider and cannot relabel a callback-triggered init. */
    @Test
    fun `session bridge checks module and runtime gates after host context capture`() {
        val module = BlockingLifecycleModule()
        var active = true
        Apm.register(module)
        // This gate rejects the only emit, so the runtime never materializes a version-4
        // WAL database. Opening a second SQLiteOpenHelper afterwards would see version 0
        // and fail a read-only upgrade under Robolectric's legacy SQLite shadow.
        // Materialize and hold the inspector first so the SDK opens an existing schema.
        val inspector = SQLiteEventStore(EventDbHelper(application))
        try {
            assertEquals(0, inspector.pendingCount())
            Apm.init(application, strictConfig().copy(
                bizContextProvider = BizContextProvider {
                    active = false
                    mapOf(TEST_FIELD to BLOCKING_VALUE)
                }
            ))
            val accepted = module.context.emitInSession(ApmEvent(module = TEST_MODULE, name = TEST_EVENT), true) { handoff ->
                if (active) handoff() else false
            }
            assertFalse(accepted)
            assertEquals(0, inspector.pendingCount())
        } finally {
            inspector.close()
        }
    }

    /** Session delivery retains contextual metadata and supplies the actual current process name. */
    @Test
    fun `session bridge preserves default business and explicit context`() {
        val module = BlockingLifecycleModule()
        Apm.register(module)
        Apm.init(application, strictConfig().copy(
            defaultContext = mapOf("default" to "bundled"),
            bizContextProvider = BizContextProvider { mapOf("business" to "captured") }
        ))
        assertTrue(module.context.emitInSession(
            ApmEvent(module = TEST_MODULE, name = TEST_EVENT, globalContext = mapOf("explicit" to "module")),
            critical = true
        ))
        val inspector = SQLiteEventStore(EventDbHelper(application))
        try {
            val stored = inspector.readPending(1).single().event
            assertEquals(module.context.processName, stored.processName)
            assertFalse(stored.processName.isEmpty())
            assertEquals(mapOf("default" to "bundled", "business" to "captured", "explicit" to "module"), stored.globalContext)
        } finally {
            inspector.close()
        }
    }

    /** Holds a real lifecycle callback open so overlap assertions do not depend on thread timing. */
    private fun verifyCleanupExclusion(revoke: Boolean, concurrentRevoke: Boolean = false) {
        val module = BlockingLifecycleModule()
        Apm.register(module)
        Apm.init(application, strictConfig())
        module.blockStop.set(true)
        val result = AtomicReference<ConsentRevocationResult>()
        val failure = AtomicReference<Throwable>()
        val cleanup = Thread {
            try {
                if (revoke) result.set(Apm.revokeCollectionConsent(application)) else Apm.stop()
            } catch (error: Throwable) {
                // Surface test-thread failures on the assertion thread after releasing the latch.
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(module.stopEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(Apm.isInitialized())
            if (concurrentRevoke) {
                val concurrentResult = Apm.revokeCollectionConsent(application)
                assertFalse(concurrentResult.storageCleared)
                assertFalse(concurrentResult.ipcFilesCleared)
            }
            if (revoke || concurrentRevoke) {
                assertFalse(module.context.emitCriticalSync(ApmEvent(module = TEST_MODULE, name = TEST_EVENT)))
                Apm.grantCollectionConsent()
            }
            assertThrows(IllegalStateException::class.java) { Apm.init(application, strictConfig()) }
            assertEquals(1, module.startCount.get())
        } finally {
            module.stopRelease.countDown()
            cleanup.join(JOIN_MS)
        }
        assertFalse(cleanup.isAlive)
        assertEquals(null, failure.get())
        if (revoke) assertTrue(result.get().storageCleared)
        if (concurrentRevoke) {
            assertTrue(Apm.revokeCollectionConsent(application).storageCleared)
            Apm.grantCollectionConsent()
        }
        Apm.init(application, strictConfig())
        assertTrue(Apm.isInitialized())
        assertEquals(2, module.startCount.get())
    }

    /** Module with one optional blocked stop; later test teardown never waits on the same latch. */
    private class BlockingLifecycleModule : ApmModule {
        /** A unique name avoids collisions with modules retained across singleton integration tests. */
        override val name = "lifecycle-" + UUID.randomUUID().toString()
        /** Current context retained to verify old dispatcher admission closure. */
        lateinit var context: ApmContext
        /** Counts completed starts. */
        val startCount = AtomicInteger()
        /** Enables exactly one blocked stop. */
        val blockStop = AtomicBoolean(false)
        /** Announces entry to the controlled host callback. */
        val stopEntered = CountDownLatch(1)
        /** Releases the host callback after overlap assertions. */
        val stopRelease = CountDownLatch(1)
        /** Retains the current context just like a real module. */
        override fun onInitialize(context: ApmContext) { this.context = context }
        /** Records each session's start. */
        override fun onStart() { startCount.incrementAndGet() }
        /** Blocks only when the test armed this stop. */
        override fun onStop() {
            if (blockStop.compareAndSet(true, false)) {
                stopEntered.countDown()
                check(stopRelease.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            }
        }
    }

    /** Provider retaining an already captured callback to simulate delayed background completion. */
    private class RetainingManagedProvider : ManagedDynamicConfigProvider {
        /** Callback which remains callable after provider stop. */
        var callback: (() -> Unit)? = null
        /** Counts reconciliation reads without changing any policy. */
        val booleanReads = AtomicInteger()
        /** Records boolean policy reads. */
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            booleanReads.incrementAndGet()
            return defaultValue
        }
        /** Leaves numeric policy unchanged. */
        override fun getLongValue(key: String, defaultValue: Long): Long = defaultValue
        /** Leaves floating policy unchanged. */
        override fun getFloatValue(key: String, defaultValue: Float): Float = defaultValue
        /** Leaves text policy unchanged. */
        override fun getString(key: String, defaultValue: String): String = defaultValue
        /** Captures the callback a background operation would retain. */
        override fun start(onConfigChanged: () -> Unit) { callback = onConfigChanged }
        /** Simulates a callback already in flight when polling stops. */
        override fun stop() = Unit
    }

    /** Reentrant revocation cannot claim erase while the outer custom append still owns delivery. */
    @Test
    fun `cleanup cannot run inside a reentrant host handoff`() {
        var erased = false
        EventDeliveryBarrier.handoff({ false }) {
            assertEquals(null, EventDeliveryBarrier.erase { erased = true })
        }
        assertFalse(erased)
        assertEquals(true, EventDeliveryBarrier.erase { true })
    }

    /** A blocked store produces an explicit bounded failure, then permits a later cleanup retry. */
    @Test
    fun `cleanup times out while a prior handoff is running`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Thread {
            EventDeliveryBarrier.handoff({ false }) {
                entered.countDown()
                release.await()
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(null, EventDeliveryBarrier.erase { true })
        } finally {
            release.countDown()
            writer.join(JOIN_MS)
        }
        assertFalse(writer.isAlive)
        assertEquals(true, EventDeliveryBarrier.erase { true })
    }

    /** A delayed async sanitizer cannot reopen the outbox after successful erasure. */
    @Test
    fun `revocation fences delayed async writes`() = verifyLateWrite(critical = false)

    /** Synchronous critical callers participate in the same erasure barrier as the worker. */
    @Test
    fun `revocation fences delayed critical writes`() = verifyLateWrite(critical = true)

    /** Granting a new session must not reopen the permanent gate on an old dispatcher. */
    @Test
    fun `new grant cannot admit an old critical write`() =
        verifyLateWrite(critical = true, grantAgain = true)

    /** Dormant cleanup also stays empty when a graceful stop previously timed out in host code. */
    @Test
    fun `dormant erase fences stopped async writes`() =
        verifyLateWrite(critical = false, stopFirst = true)

    /** Reproduces a host callback that outlives bounded shutdown using the real SQLite store. */
    private fun verifyLateWrite(
        critical: Boolean,
        grantAgain: Boolean = false,
        stopFirst: Boolean = false
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = AtomicReference<Thread>()
        val accepted = AtomicReference<Boolean>()
        val rule = SanitizationRule { value ->
            if (value == BLOCKING_VALUE) {
                writer.set(Thread.currentThread())
                entered.countDown()
                // Model a host callback which does not respond to SDK interruption.
                var waiting = true
                while (waiting) {
                    try {
                        release.await()
                        waiting = false
                    } catch (_: InterruptedException) {
                        // Only the test's release latch ends this host operation.
                    }
                }
            }
            value
        }
        var inspector: SQLiteEventStore? = null
        try {
            Apm.init(application, strictConfig().copy(
                customSanitizationRules = listOf(rule),
                diagnostics = DiagnosticsConfig(enabled = false)
            ))
            if (critical) {
                Thread({
                    accepted.set(Apm.emitCriticalSync(
                        TEST_MODULE, TEST_EVENT, fields = mapOf(TEST_FIELD to BLOCKING_VALUE)
                    ))
                }, TEST_THREAD).apply { isDaemon = true; start() }
            } else {
                Apm.emit(TEST_MODULE, TEST_EVENT, fields = mapOf(TEST_FIELD to BLOCKING_VALUE))
            }
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            if (stopFirst) Apm.stop()
            val result = Apm.revokeCollectionConsent(application)
            inspector = SQLiteEventStore(EventDbHelper(application))
            assertTrue(result.storageCleared)
            assertEquals(0, inspector.pendingCount())
            if (grantAgain) {
                Apm.grantCollectionConsent()
                Apm.init(application, strictConfig())
            }
            release.countDown()
            writer.get().join(JOIN_MS)
            assertFalse(writer.get().isAlive)
            if (critical) assertEquals(false, accepted.get())
            assertEquals(0, inspector.pendingCount())
        } finally {
            release.countDown()
            writer.get()?.join(JOIN_MS)
            inspector?.clear()
            inspector?.close()
        }
    }

    /** Returns a strict configuration whose uploader keeps rows pending until consent is revoked. */
    private fun strictConfig(): ApmConfig = ApmConfig(
        runtimeProfile = ApmRuntimeProfile.PRODUCTION_STRICT,
        initialCollectionConsent = CollectionConsent.GRANTED,
        uploader = RejectingUploader(),
        enableSelfMonitoring = false,
        retryBaseDelayMs = RETRY_DELAY_MS
    )

    /** Uploader that keeps the durable row pending without performing network IO. */
    private class RejectingUploader : ApmUploader {
        /** Rejects every event so the outbox owns it until privacy erasure. */
        override fun upload(event: ApmEvent): Boolean = false
    }

    companion object {
        /** Latch timeout for local SQLite integration. */
        private const val AWAIT_SECONDS = 10L
        /** Bounded join after releasing host code. */
        private const val JOIN_MS = 5_000L
        /** Synthetic non-PII callback sentinel. */
        private const val BLOCKING_VALUE = "blocked-host-rule"
        /** Stable test module. */
        private const val TEST_MODULE = "privacy"
        /** Stable delayed event name. */
        private const val TEST_EVENT = "late_write"
        /** Benign field which reaches custom text rules. */
        private const val TEST_FIELD = "value"
        /** Name of the simulated crash caller. */
        private const val TEST_THREAD = "consent-critical-test"
        /** Long delay prevents a retry loop from racing the immediate revocation assertion. */
        private const val RETRY_DELAY_MS = 60_000L
        /** Short health cadence used to reach a real SDK timer callback deterministically. */
        private const val HEALTH_INTERVAL_MS = 10L
    }
}
