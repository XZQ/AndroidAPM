package com.apm.core

import android.app.Application
import com.apm.core.diagnostics.DiagnosticsConfig
import com.apm.core.throttle.ManagedDynamicConfigProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Verifies managed dynamic-config lifecycle and live module kill-switch reconciliation. */
@RunWith(RobolectricTestRunner::class)
class ManagedDynamicConfigIntegrationTest {

    /** Stops a runtime left by another singleton integration test. */
    @Before
    fun setUp() {
        Apm.stop()
    }

    /** Stops provider and SDK threads after every assertion path. */
    @After
    fun tearDown() {
        Apm.stop()
    }

    /** Verified global and module flags stop and restart a registered module without re-init. */
    @Test
    fun `managed provider reconciles module lifecycle and stops with apm`() {
        val provider = RecordingManagedProvider()
        val module = RecordingModule(UNIQUE_MODULE_NAME)
        Apm.register(module)
        val application = RuntimeEnvironment.getApplication() as Application

        Apm.init(
            application,
            ApmConfig(
                storageType = StorageType.FILE,
                diagnostics = DiagnosticsConfig(enabled = false),
                enableSelfMonitoring = false,
                enableRetry = false,
                dynamicConfigProvider = provider
            )
        )

        assertTrue(provider.started)
        assertEquals(1, module.startCount)
        provider.values[GLOBAL_ENABLED_KEY] = false
        provider.publishChange()
        assertEquals(1, module.stopCount)
        provider.values[GLOBAL_ENABLED_KEY] = true
        provider.publishChange()
        assertEquals(2, module.startCount)

        Apm.stop()
        assertTrue(provider.stopped)
        assertEquals(2, module.stopCount)
    }

    /** Mutable provider exposing a synchronous test callback. */
    private class RecordingManagedProvider : ManagedDynamicConfigProvider {
        /** Current test-controlled boolean values. */
        val values = mutableMapOf<String, Boolean>()

        /** Whether core called start. */
        var started: Boolean = false

        /** Whether core called stop. */
        var stopped: Boolean = false

        /** Callback installed by core. */
        private var callback: (() -> Unit)? = null

        /** Returns the test value or caller default. */
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] ?: defaultValue

        /** This test does not publish long values. */
        override fun getLongValue(key: String, defaultValue: Long): Long = defaultValue

        /** This test does not publish float values. */
        override fun getFloatValue(key: String, defaultValue: Float): Float = defaultValue

        /** This test does not publish string values. */
        override fun getString(key: String, defaultValue: String): String = defaultValue

        /** Retains the core callback without creating a thread. */
        override fun start(onConfigChanged: () -> Unit) {
            started = true
            callback = onConfigChanged
        }

        /** Drops the callback and records lifecycle completion. */
        override fun stop() {
            stopped = true
            callback = null
        }

        /** Publishes one synchronous effective-view change. */
        fun publishChange() {
            callback?.invoke()
        }
    }

    /** Minimal module counting lifecycle transitions. */
    private class RecordingModule(
        /** Stable unique module name. */
        override val name: String
    ) : ApmModule {
        /** Number of successful start calls. */
        var startCount: Int = 0

        /** Number of successful stop calls. */
        var stopCount: Int = 0

        /** No initialization state is required for this lifecycle test. */
        override fun onInitialize(context: ApmContext) = Unit

        /** Records a start or dynamic restart. */
        override fun onStart() {
            startCount++
        }

        /** Records a dynamic or final stop. */
        override fun onStop() {
            stopCount++
        }
    }

    companion object {
        /** Unique global singleton module name for this test class. */
        private const val UNIQUE_MODULE_NAME = "managed-dynamic-config-test"

        /** Global emergency enable key consumed by Apm. */
        private const val GLOBAL_ENABLED_KEY = "apm.enabled"
    }
}
