package com.apm.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import com.apm.core.Apm
import com.apm.core.ApmConfig
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.core.diagnostics.DiagnosticsConfig
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.model.ApmEvent
import com.apm.model.ApmOccurrenceContext
import com.apm.model.SerializationFormat
import com.apm.uploader.ApmUploader
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/** Exercises the real CrashModule, core dispatcher and uploader with OS exit history supplied by a shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [CrashExitHistoryTest.RecordingActivityManager::class])
class CrashExitHistoryTest {
    /** Current Robolectric application. */
    private lateinit var application: Application
    /** Shadow retaining the actual SDK's process-summary write requests. */
    private lateinit var manager: RecordingActivityManager
    /** Completed custom-uploader observations; no HTTP or production acceptance is implied. */
    private val uploaded = LinkedBlockingQueue<ApmEvent>()
    /** Explicit fake old release and anonymous installation. */
    private val oldIdentity = ApmOccurrenceContext("1.0", "1", "old-build", "release", "test-old-install")
    /** Current release intentionally differs from the OS historical record. */
    private val currentIdentity = ApmOccurrenceContext("2.0", "2", "new-build", "release", "test-new-install")
    /** History remains inside the real SQLite worker's seven-day retention window. */
    private var historicalTime = 0L

    /** Isolates test-owned modules and the historical watermark across cases. */
    @Before
    fun setUp() {
        Apm.stop()
        Apm.grantCollectionConsent()
        clearTestModules()
        application = RuntimeEnvironment.getApplication()
        historicalTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        application.getSharedPreferences("apm_crash_exit_info", Context.MODE_PRIVATE).edit().clear().commit()
        manager = Shadow.extract(application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
    }

    /** Cancels workers and restores consent and module registration for subsequent tests. */
    @After
    fun tearDown() {
        Apm.stop()
        Apm.grantCollectionConsent()
        clearTestModules()
    }

    /** An upgrade and a worker-to-main replay retain every recorded occurrence field and OS process. */
    @Test
    fun `historical worker exit retains old identity and clears current summary on stop`() {
        addRecord(ExitOccurrenceSummary.encode(oldIdentity))
        val module = CrashModule(CrashConfig(enableJavaCrash = false))
        start(module)
        awaitCollector(module)
        val event = awaitExit()
        Apm.stop()

        assertEquals(oldIdentity, event.occurrence)
        assertEquals(historicalTime, event.timestamp)
        assertEquals(historicalTime, event.fields["exitTimestamp"])
        assertEquals("${application.packageName}:worker", event.processName)
        assertEquals(event.processName, event.fields["exitProcessName"])
        assertEquals("unknown", event.threadName)
        assertEquals("RECORDED", event.fields["occurrenceStatus"])
        assertEquals(currentIdentity, ExitOccurrenceSummary.decode(manager.writes.first()))
        assertNull(manager.summary)
        assertEquals(2, manager.writes.size)
    }

    /** Unknown history is observable as a drop without leaking a fabricated current release. */
    @Test
    fun `strict v3 unknown history is counted without uploading an attributed event`() {
        addRecord(null)
        val module = CrashModule(CrashConfig(enableJavaCrash = false))
        val context = start(module)
        awaitCollector(module)
        Apm.stop()

        assertTrue(uploaded.none { it.name == ExitReasonCollector.EVENT_APP_EXIT })
        assertEquals(1L, context.selfMonitor!!.getDropCount(SdkDropReason.HISTORICAL_OCCURRENCE_UNAVAILABLE))
        assertEquals(historicalTime, PrefsExitTimestampStore(application).lastProcessedMs())
    }

    /** An explicit V2 runtime preserves unknown history and leaves another component's slot alone. */
    @Test
    fun `v2 retains unknown process history without writing an identity summary`() {
        addRecord(null)
        val foreign = "host-owned-slot".toByteArray()
        manager.summary = foreign
        val module = CrashModule(CrashConfig(enableJavaCrash = false))
        start(module, format = SerializationFormat.PROTOBUF_ENVELOPE_V2)
        awaitCollector(module)
        val event = awaitExit()
        Apm.stop()

        assertNull(event.occurrence)
        assertEquals("UNKNOWN", event.fields["occurrenceStatus"])
        assertEquals("${application.packageName}:worker", event.processName)
        assertEquals(historicalTime, event.timestamp)
        assertArrayEquals(foreign, manager.summary)
        assertTrue(manager.writes.isEmpty())
    }

    /** Hosts can reserve the process slot without disabling recovery of previously recorded identity. */
    @Test
    fun `write opt out preserves host summary during start and stop`() {
        addRecord(ExitOccurrenceSummary.encode(oldIdentity))
        val foreign = "host-owned-slot".toByteArray()
        manager.summary = foreign
        val module = CrashModule(CrashConfig(enableJavaCrash = false), writeExitIdentitySummary = false)
        start(module)
        awaitCollector(module)
        val event = awaitExit()
        Apm.stop()

        assertEquals(oldIdentity, event.occurrence)
        assertArrayEquals(foreign, manager.summary)
        assertTrue(manager.writes.isEmpty())
    }

    /** An oversized identity clears a stale own-process value instead of recording a shortened build. */
    @Test
    fun `oversized identity never leaves a stale summary for future exits`() {
        manager.summary = ExitOccurrenceSummary.encode(oldIdentity)
        val module = CrashModule(CrashConfig(enableJavaCrash = false))
        start(module, identity = currentIdentity.copy(appBuild = "x".repeat(256)))
        awaitCollector(module)

        assertEquals(1, manager.writes.size)
        assertNull(manager.summary)
    }

    /** Revocation removes the current process's SDK-owned summary as part of module shutdown. */
    @Test
    fun `consent revocation clears the active process summary`() {
        val module = CrashModule(CrashConfig(enableJavaCrash = false))
        start(module)
        awaitCollector(module)
        assertNotNull(manager.summary)

        Apm.revokeCollectionConsent()

        assertNull(manager.summary)
    }

    /** A platform trace that ignores interruption cannot publish through a stopped or new session. */
    @Test
    fun `late trace is cancelled before a new runtime can receive it`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val trace = object : InputStream() {
            /** Simulates a blocking OS stream that does not cooperate with Thread.interrupt. */
            override fun read(): Int {
                entered.countDown()
                while (true) {
                    try {
                        check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "trace release timed out" }
                        return -1
                    } catch (_: InterruptedException) {
                        // Deliberately exercise the session gate rather than cooperative cancellation.
                    }
                }
            }
        }
        addRecord(ExitOccurrenceSummary.encode(oldIdentity), trace)
        val module = CrashModule(CrashConfig(enableJavaCrash = false))
        start(module)
        val oldThread = collectorThread(module)
        try {
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            Apm.stop()
            clearTestModules()
            start(CrashModule(CrashConfig(enableJavaCrash = false, collectExitInfo = false)))
            release.countDown()
            oldThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse(oldThread.isAlive)
            Apm.stop()
            assertTrue(uploaded.none { it.name == ExitReasonCollector.EVENT_APP_EXIT })
            assertEquals(0L, PrefsExitTimestampStore(application).lastProcessedMs())
        } finally {
            release.countDown()
            oldThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        }
    }

    /** Supplies a worker record with an optional blocking ANR trace to AndroidExitInfoSource. */
    private fun addRecord(summary: ByteArray?, trace: InputStream? = null) {
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setTimestamp(historicalTime).setReason(if (trace == null) 4 else ExitReasonCollector.REASON_ANR)
            .setProcessName("${application.packageName}:worker").setPid(12345).setImportance(100)
            .setProcessStateSummary(summary)
        if (trace != null) builder.setTraceInputStream(trace)
        manager.addApplicationExitInfo(builder.build())
    }

    /** Starts the real runtime and exposes its normal module context for value-free drop counters. */
    private fun start(
        module: CrashModule,
        identity: ApmOccurrenceContext = currentIdentity,
        format: SerializationFormat = SerializationFormat.PROTOBUF_ENVELOPE_V3
    ): ApmContext {
        lateinit var captured: ApmContext
        Apm.register(object : ApmModule {
            /** Test-only module name. */
            override val name = "history_test_context"
            /** Captures the actual runtime context without new production test hooks. */
            override fun onInitialize(context: ApmContext) { captured = context }
            /** Owns no background resources. */
            override fun onStart() = Unit
            /** Owns no background resources. */
            override fun onStop() = Unit
        })
        val config = ApmConfig(
            uploader = object : ApmUploader {
                /** Records the real dispatcher's delivery. */
                override fun upload(event: ApmEvent): Boolean = uploaded.offer(event)
            },
            serializationFormat = format,
            diagnostics = DiagnosticsConfig(enabled = false)
        )
        if (format == SerializationFormat.PROTOBUF_ENVELOPE_V3) Apm.init(application, config, identity)
        else Apm.init(application, config)
        Apm.register(module)
        return captured
    }

    /** Waits for a complete collector result; an arbitrary sleep would hide missing delivery. */
    private fun awaitCollector(module: CrashModule) {
        val thread = collectorThread(module)
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse("collector did not finish", thread.isAlive)
    }

    /** A graceful SDK stop retains pending SQLite rows; wait for transport evidence before stopping. */
    private fun awaitExit(): ApmEvent {
        val event = uploaded.poll(WAIT_SECONDS, TimeUnit.SECONDS)
        assertNotNull("historical event never reached uploader", event)
        assertEquals(ExitReasonCollector.EVENT_APP_EXIT, event!!.name)
        return event
    }

    /** Retrieves only the worker handle to join the real implementation deterministically. */
    private fun collectorThread(module: CrashModule): Thread =
        CrashModule::class.java.getDeclaredField("exitCollectorThread").apply { isAccessible = true }
            .get(module) as Thread

    /** Removes test-owned registrations after stopping; Apm intentionally retains them across init. */
    private fun clearTestModules() {
        val field = Apm::class.java.getDeclaredField("modules").apply { isAccessible = true }
        (field.get(Apm) as MutableCollection<*>).clear()
    }

    /** Extends Robolectric's historical-exit support with its missing process-summary setter. */
    @Implements(ActivityManager::class)
    class RecordingActivityManager : ShadowActivityManager() {
        /** Current per-process platform slot. */
        var summary: ByteArray? = null
        /** Exact writes from real CrashModule lifecycle methods. */
        val writes = mutableListOf<ByteArray?>()
        /** Enforces the Android API byte limit while retaining a copy of each request. */
        @Implementation(minSdk = 30)
        fun setProcessStateSummary(value: ByteArray?) {
            require(value == null || value.size <= ExitOccurrenceSummary.MAX_BYTES)
            summary = value?.copyOf()
            writes += summary
        }
    }

    companion object {
        /** Bounded thread/latch deadline; these tests never depend on timing for success. */
        private const val WAIT_SECONDS = 10L
    }
}
