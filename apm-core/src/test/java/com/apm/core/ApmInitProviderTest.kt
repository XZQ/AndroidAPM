package com.apm.core

import android.app.Application
import android.content.Context
import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Lifecycle tests for optional provider-based APM initialization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApmInitProviderTest {
    /** Application whose manifest metadata is controlled by each test. */
    private lateinit var application: Application

    /** Resets global APM state and manifest metadata before each provider creation. */
    @Before
    fun setUp() {
        Apm.stop()
        application = RuntimeEnvironment.getApplication()
        application.applicationInfo.metaData = Bundle()
    }

    /** Stops background runtime components after every test. */
    @After
    fun tearDown() {
        Apm.stop()
    }

    /** Missing metadata preserves the supported manual-initialization path. */
    @Test
    fun `missing metadata skips auto initialization`() {
        createProvider()

        assertFalse(Apm.isInitialized())
    }

    /** A valid configuration provider initializes APM before application startup. */
    @Test
    fun `valid provider metadata initializes apm`() {
        application.applicationInfo.metaData.putString(CONFIG_CLASS_KEY, TestConfigProvider::class.java.name)

        createProvider()

        assertTrue(Apm.isInitialized())
    }

    /** An invalid provider class is isolated and cannot crash host startup. */
    @Test
    fun `invalid provider metadata is isolated`() {
        application.applicationInfo.metaData.putString(CONFIG_CLASS_KEY, String::class.java.name)

        createProvider()

        assertFalse(Apm.isInitialized())
    }

    /** Creates the provider through the same lifecycle callback used by Android. */
    private fun createProvider(): ApmInitProvider =
        Robolectric.buildContentProvider(ApmInitProvider::class.java).create().get()

    /** Minimal no-argument configuration provider used by the reflection path. */
    class TestConfigProvider : ApmConfigProvider {
        /** Returns a local Logcat configuration suitable for a unit-test process. */
        override fun provideConfig(context: Context): ApmConfig = ApmConfig()
    }

    companion object {
        /** Manifest metadata key consumed by [ApmInitProvider]. */
        private const val CONFIG_CLASS_KEY = "com.apm.config_class"
    }
}
