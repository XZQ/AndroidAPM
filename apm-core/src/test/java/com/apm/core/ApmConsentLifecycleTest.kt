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
    }
}
