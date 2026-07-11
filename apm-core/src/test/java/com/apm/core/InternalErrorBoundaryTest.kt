package com.apm.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Recoverable and fatal behavior tests for core host-safety boundaries. */
class InternalErrorBoundaryTest {

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
