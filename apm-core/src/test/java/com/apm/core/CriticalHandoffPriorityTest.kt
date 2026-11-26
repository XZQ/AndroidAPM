package com.apm.core

import android.app.Application
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import com.apm.storage.EventStore
import com.apm.uploader.ApmUploader
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Dedicated critical hand-off priority and child-process self-monitoring contract tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CriticalHandoffPriorityTest {
    /** Every caller-selected priority is promoted so the dedicated path cannot be weakened. */
    @Test
    fun `critical handoff always uses critical priority`() {
        for (priority in ApmPriority.values()) {
            assertEquals(ApmPriority.CRITICAL, criticalHandoffPriority(priority))
        }
    }

    /** Direct dispatcher/context critical sync promotes lower priority and freezes mutable maps. */
    @Test
    fun `direct critical sync promotes priority and child emit records self monitor count`() {
        val stored = ArrayList<ApmEvent>()
        val store = object : EventStore {
            override fun append(event: ApmEvent) {
                stored += event
            }

            override fun readRecent(limit: Int): List<String> = emptyList()
            override fun clear() = stored.clear()
        }
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = object : ApmUploader {
                override fun upload(event: ApmEvent): Boolean = true
            },
            logger = NoopLogger,
            piiSanitizer = null
        )
        val ipcDir = Files.createTempDirectory("apm-critical-ipc-test").toFile()
        val childCoordinator = ProcessEventCoordinator(ipcDir = ipcDir, isUploaderProcess = false)
        childCoordinator.start()
        try {
            val mutableFields = mutableMapOf<String, Any?>("stage" to "before")
            val accepted = dispatcher.dispatchCriticalSync(
                ApmEvent(
                    module = "anr",
                    name = "anr_detected",
                    priority = ApmPriority.NORMAL,
                    fields = mutableFields
                )
            )
            mutableFields["stage"] = "mutated"

            assertTrue(accepted)
            assertEquals(1, stored.size)
            assertEquals(ApmPriority.CRITICAL, stored.single().priority)
            assertEquals("before", stored.single().fields["stage"])

            val application: Application = RuntimeEnvironment.getApplication()
            val childMonitor = SdkSelfMonitor()
            val childContext = ApmContext(
                application = application,
                config = ApmConfig(),
                processName = "com.example:remote",
                logger = NoopLogger,
                dispatcher = dispatcher,
                processCoordinator = childCoordinator,
                isUploaderProcess = false
            ).also { it.selfMonitor = childMonitor }

            childContext.emit(ApmEvent(module = "network", name = "request", priority = ApmPriority.NORMAL))
            childContext.emitLazy(ApmPriority.NORMAL, "network", 256L) {
                ApmEvent(module = "network", name = "lazy_request", priority = ApmPriority.NORMAL)
            }
            assertTrue(
                childContext.emitCriticalSync(
                    ApmEvent(module = "crash", name = "java_crash", priority = ApmPriority.HIGH)
                )
            )
            assertEquals(3L, childMonitor.getTotalEmitCount())
        } finally {
            childCoordinator.stop()
            dispatcher.shutdown()
            ipcDir.deleteRecursively()
        }
    }

    /** Silent logger stub for isolated unit verification. */
    private object NoopLogger : ApmLogger {
        override fun d(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(message: String, throwable: Throwable?) = Unit
    }
}
