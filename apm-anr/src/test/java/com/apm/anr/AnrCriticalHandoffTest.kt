package com.apm.anr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the ANR synchronous local hand-off boundary. */
class AnrCriticalHandoffTest {
    /** A completed local hand-off succeeds without publishing a false failure diagnostic. */
    @Test
    fun `successful critical handoff does not report failure`() {
        var attempts = 0
        var failures = 0

        val result = executeCriticalAnrHandoff(
            criticalHandoff = {
                attempts += 1
                true
            },
            onFailure = { failures += 1 }
        )

        assertTrue(result)
        assertTrue(attempts == 1)
        assertTrue(failures == 0)
    }

    /** A rejected local hand-off remains visible while still avoiding retries on the ANR thread. */
    @Test
    fun `failed critical handoff reports once`() {
        var attempts = 0
        var failures = 0

        val result = executeCriticalAnrHandoff(
            criticalHandoff = {
                attempts += 1
                false
            },
            onFailure = { failures += 1 }
        )

        assertFalse(result)
        assertTrue(attempts == 1)
        assertTrue(failures == 1)
    }
}
