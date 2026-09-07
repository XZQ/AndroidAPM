package com.apm.core

import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import com.apm.model.ApmOccurrenceContext
import com.apm.model.ApmNativeFrameIdentity
import com.apm.model.SerializationFormat
import com.apm.storage.EventStore
import com.apm.uploader.ApmUploader
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

/** Non-uploader-process critical IPC hand-off accounting tests. */
@RunWith(RobolectricTestRunner::class)
class ApmContextCriticalHandoffTest {
    /** V3 freezes the init-time release while retaining module-provided native frame identity. */
    @Test
    fun `v3 context binds occurrence before durable critical handoff`() {
        val store = CapturingStore()
        val dispatcher = ApmDispatcher(store, NoOpUploader, NoOpLogger)
        try {
            val configured = ApmOccurrenceContext(
                serviceVersion = "1.0.0",
                versionCode = "100",
                appBuild = "old-build",
                variant = "release",
                installationId = "install-old"
            )
            val frame = ApmNativeFrameIdentity("arm64-v8a", "abcd", "libsample.so", 42L)
            val context = ApmContext(
                application = RuntimeEnvironment.getApplication(),
                config = ApmConfig(
                    serializationFormat = SerializationFormat.PROTOBUF_ENVELOPE_V3
                ),
                processName = "main",
                logger = NoOpLogger,
                dispatcher = dispatcher,
                occurrenceContext = configured
            )

            assertEquals(
                true,
                context.emitCriticalSync(
                    ApmEvent(
                        module = "crash",
                        name = "native_crash",
                        priority = ApmPriority.CRITICAL
                    ).withOccurrenceContext(ApmOccurrenceContext(nativeFrames = listOf(frame)))
                )
            )

            assertEquals(configured.copy(nativeFrames = listOf(frame)), store.event?.occurrence)
        } finally {
            dispatcher.shutdown()
        }
    }

    /** A stopped IPC coordinator rejects synchronously and records exact reason and priority. */
    @Test
    fun `failed critical ipc handoff is classified`() {
        val ipcDirectory = createTempDirectory("apm-critical-ipc").toFile()
        val coordinator = ProcessEventCoordinator(ipcDirectory, isUploaderProcess = false)
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(NoOpStore, NoOpUploader, NoOpLogger, selfMonitor = selfMonitor)
        try {
            val context = ApmContext(
                application = RuntimeEnvironment.getApplication(),
                config = ApmConfig(),
                processName = "remote",
                logger = NoOpLogger,
                dispatcher = dispatcher,
                processCoordinator = coordinator,
                isUploaderProcess = false
            ).also { it.selfMonitor = selfMonitor }
            assertFalse(
                context.emitCriticalSync(
                    ApmEvent(module = "anr", name = "anr_detected", priority = ApmPriority.CRITICAL)
                )
            )
            assertEquals(1L, selfMonitor.getTotalEmitCount())
            assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.IPC_HANDOFF_FAILURE))
            assertEquals(1L, selfMonitor.getDropCount(ApmPriority.CRITICAL))
        } finally {
            coordinator.stop()
            dispatcher.shutdown()
            ipcDirectory.deleteRecursively()
        }
    }

    /** Critical IPC byte rejection is counted once with the exact file-budget reason. */
    @Test
    fun `critical ipc byte rejection preserves exact reason`() {
        val ipcDirectory = createTempDirectory("apm-critical-ipc-budget").toFile()
        val coordinator = ProcessEventCoordinator(
            ipcDir = ipcDirectory,
            isUploaderProcess = false,
            maxEventPayloadBytes = 1024 * 1024,
            maxFileBytes = 64L,
            maxDirectoryBytes = 1024L
        )
        val selfMonitor = SdkSelfMonitor()
        val dispatcher = ApmDispatcher(NoOpStore, NoOpUploader, NoOpLogger, selfMonitor = selfMonitor)
        try {
            coordinator.start()
            val context = ApmContext(
                application = RuntimeEnvironment.getApplication(),
                config = ApmConfig(),
                processName = "remote",
                logger = NoOpLogger,
                dispatcher = dispatcher,
                processCoordinator = coordinator,
                isUploaderProcess = false
            ).also { it.selfMonitor = selfMonitor }

            assertFalse(
                context.emitCriticalSync(
                    ApmEvent(
                        module = "anr",
                        name = "oversized_anr",
                        priority = ApmPriority.CRITICAL,
                        fields = mapOf("stack" to "x".repeat(256))
                    )
                )
            )

            assertEquals(1L, selfMonitor.getTotalDropCount())
            assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.IPC_FILE_BYTE_BUDGET))
            assertEquals(0L, selfMonitor.getDropCount(SdkDropReason.IPC_HANDOFF_FAILURE))
            assertEquals(1L, selfMonitor.getDropCount(ApmPriority.CRITICAL))
        } finally {
            coordinator.stop()
            dispatcher.shutdown()
            ipcDirectory.deleteRecursively()
        }
    }

    /** Stateless event store unused by the remote-process IPC branch. */
    private object NoOpStore : EventStore {
        /** Accepts an event if unexpectedly invoked. */
        override fun append(event: ApmEvent) = Unit
        /** Returns no diagnostic rows. */
        override fun readRecent(limit: Int): List<String> = emptyList()
        /** Clears no state. */
        override fun clear() = Unit
    }

    /** Stores the latest append for occurrence-bound hand-off assertions. */
    private class CapturingStore : EventStore {
        /** Latest synchronously persisted event. */
        var event: ApmEvent? = null

        /** Captures one event. */
        override fun append(event: ApmEvent) {
            this.event = event
        }

        /** Returns no textual diagnostics. */
        override fun readRecent(limit: Int): List<String> = emptyList()

        /** Clears the captured event. */
        override fun clear() {
            event = null
        }
    }

    /** Stateless uploader unused by the remote-process IPC branch. */
    private object NoOpUploader : ApmUploader {
        /** Accepts an event if unexpectedly invoked. */
        override fun upload(event: ApmEvent): Boolean = true
    }

    /** Silent logger used to avoid Android Log dependencies in assertions. */
    private object NoOpLogger : ApmLogger {
        /** Ignores debug output. */
        override fun d(message: String) = Unit
        /** Ignores warning output. */
        override fun w(message: String) = Unit
        /** Ignores error output. */
        override fun e(message: String, throwable: Throwable?) = Unit
    }
}
