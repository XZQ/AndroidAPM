package com.apm.core

import android.app.Application
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
        /** Long delay prevents a retry loop from racing the immediate revocation assertion. */
        private const val RETRY_DELAY_MS = 60_000L
    }
}
