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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** Async business-context refresh is wired to public lifecycle and stops with APM. */
    @Test
    fun `async business context refresh follows apm lifecycle`() {
        val firstRefresh = CountDownLatch(1)
        val explicitRefresh = CountDownLatch(1)
        val calls = AtomicInteger(0)
        Apm.init(
            application,
            ApmConfig(
                storageType = StorageType.FILE,
                enableSelfMonitoring = false,
                bizContextProvider = BizContextProvider {
                    when (calls.incrementAndGet()) {
                        1 -> firstRefresh.countDown()
                        2 -> explicitRefresh.countDown()
                        else -> Unit
                    }
                    mapOf("user" to "test")
                },
                bizContextCaptureMode = BizContextCaptureMode.ASYNC_CACHED,
                bizContextRefreshIntervalMs = ONE_DAY_MS
            )
        )

        assertTrue(firstRefresh.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(Apm.refreshBizContext())
        assertTrue(explicitRefresh.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        Apm.stop()
        assertFalse(Apm.refreshBizContext())
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

        /** Long cadence prevents a periodic refresh from racing the explicit lifecycle request. */
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1_000L

        /** Maximum wait for one asynchronous provider callback. */
        private const val AWAIT_TIMEOUT_SECONDS = 2L
    }
}
