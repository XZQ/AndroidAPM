package com.apm.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for crash durability and uncaught-exception delegation. */
class CrashCriticalHandoffTest {
    /** A successful hand-off delegates exactly once without a failure diagnostic. */
    @Test
    fun `successful crash handoff delegates once`() {
        var attempts = 0
        var failures = 0
        var delegations = 0

        val result = executeCriticalCrashHandoff(
            criticalHandoff = {
                attempts += 1
                true
            },
            onFailure = { failures += 1 },
            delegate = { delegations += 1 }
        )

        assertTrue(result)
        assertEquals(1, attempts)
        assertEquals(0, failures)
        assertEquals(1, delegations)
    }

    /** A false hand-off is surfaced once while preserving the host crash chain. */
    @Test
    fun `rejected crash handoff reports and delegates once`() {
        val reported = mutableListOf<Exception?>()
        var delegations = 0

        val result = executeCriticalCrashHandoff(
            criticalHandoff = { false },
            onFailure = reported::add,
            delegate = { delegations += 1 }
        )

        assertFalse(result)
        assertEquals(listOf<Exception?>(null), reported)
        assertEquals(1, delegations)
    }

    /** A recoverable telemetry exception is isolated and reported without skipping delegation. */
    @Test
    fun `recoverable crash handoff failure reports original exception`() {
        val failure = IllegalStateException("disk unavailable")
        var reported: Exception? = null
        var delegations = 0

        val result = executeCriticalCrashHandoff(
            criticalHandoff = { throw failure },
            onFailure = { reported = it },
            delegate = { delegations += 1 }
        )

        assertFalse(result)
        assertSame(failure, reported)
        assertEquals(1, delegations)
    }

    /** Fatal VM errors remain visible, but never suppress the original crash handler. */
    @Test
    fun `fatal crash handoff error propagates after delegation`() {
        val fatal = OutOfMemoryError("fatal")
        var failures = 0
        var delegations = 0

        val actual = assertThrows(OutOfMemoryError::class.java) {
            executeCriticalCrashHandoff(
                criticalHandoff = { throw fatal },
                onFailure = { failures += 1 },
                delegate = { delegations += 1 }
            )
        }

        assertSame(fatal, actual)
        assertEquals(0, failures)
        assertEquals(1, delegations)
    }
}
