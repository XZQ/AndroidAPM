package com.apm.core

import com.apm.model.ApmEvent
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Recoverable and fatal behavior tests for core host-safety boundaries. */
class InternalErrorBoundaryTest {

    /** Lazy event payloads must retain the values present when emit was called. */
    @Test
    fun `event payload snapshots do not follow later host mutation`() {
        val fields = mutableMapOf<String, Any?>("status" to "before")
        val extras = mutableMapOf("source" to "before")

        val fieldSnapshot = snapshotEventFields(fields)
        val extrasSnapshot = snapshotEventExtras(extras)
        fields["status"] = "after"
        extras["source"] = "after"

        assertEquals("before", fieldSnapshot["status"])
        assertEquals("before", extrasSnapshot["source"])
    }

    /** Direct module events freeze every map at the asynchronous boundary. */
    @Test
    fun `complete event snapshots do not follow later host mutation`() {
        val fields = mutableMapOf<String, Any?>("field" to "before")
        val context = mutableMapOf("tenant" to "before")
        val extras = mutableMapOf("extra" to "before")
        val snapshot = snapshotEvent(
            ApmEvent(
                module = "test",
                name = "snapshot",
                fields = fields,
                globalContext = context,
                extras = extras
            )
        )

        fields["field"] = "after"
        context["tenant"] = "after"
        extras["extra"] = "after"

        assertEquals("before", snapshot.fields["field"])
        assertEquals("before", snapshot.globalContext["tenant"])
        assertEquals("before", snapshot.extras["extra"])
    }

    /** Invalid/regressing test timestamps cannot create negative telemetry durations. */
    @Test
    fun `monotonic duration helper clamps regressing input`() {
        assertEquals(25L, nonNegativeMonotonicDurationMillis(100L, 125L))
        assertEquals(0L, nonNegativeMonotonicDurationMillis(125L, 100L))
    }

    /** Failure in one internal-error sink must not prevent the independent sink. */
    @Test
    fun `internal error sinks are independently isolated`() {
        val diagnosticsCalls = AtomicInteger(0)

        recordInternalErrorSafely(
            selfMonitorSink = { throw IOException("self monitor") },
            diagnosticsSink = { diagnosticsCalls.incrementAndGet() }
        )
        recordInternalErrorSafely(
            selfMonitorSink = {},
            diagnosticsSink = { throw IOException("diagnostics") }
        )

        assertEquals(1, diagnosticsCalls.get())
    }

    /** Fatal VM errors from an internal-error sink remain visible. */
    @Test
    fun `internal error boundary does not swallow fatal vm error`() {
        val fatal = OutOfMemoryError("fatal sink")

        val actual = assertThrows(OutOfMemoryError::class.java) {
            recordInternalErrorSafely(selfMonitorSink = { throw fatal }, diagnosticsSink = {})
        }

        assertSame(fatal, actual)
    }

    /** Checked provider exceptions degrade to empty immutable business context. */
    @Test
    fun `checked business context exception degrades to empty context`() {
        val errors = mutableListOf<Exception>()

        val context = captureBizContextSafely(
            provider = BizContextProvider { throw IOException("provider") },
            onError = errors::add
        )

        assertEquals(emptyMap<String, String>(), context)
        assertEquals("provider", errors.single().message)
    }

    /** Lifecycle recovery catches checked exceptions but never fatal VM errors. */
    @Test
    fun `recoverable lifecycle boundary preserves fatal errors`() {
        val recoverable = mutableListOf<Exception>()
        assertEquals(false, runRecoverableBoundary(
            block = { throw IOException("lifecycle") },
            onFailure = recoverable::add
        ))
        assertEquals("lifecycle", recoverable.single().message)

        val fatal = OutOfMemoryError("fatal lifecycle")
        val actual = assertThrows(OutOfMemoryError::class.java) {
            runRecoverableBoundary(block = { throw fatal }, onFailure = {})
        }
        assertSame(fatal, actual)
    }
}
