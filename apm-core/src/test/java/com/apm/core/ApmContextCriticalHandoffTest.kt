package com.apm.core

import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
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
