package com.apm.core

import com.apm.model.ApmPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/** Dedicated critical hand-off priority contract tests. */
class CriticalHandoffPriorityTest {
    /** Every caller-selected priority is promoted so the dedicated path cannot be weakened. */
    @Test
    fun `critical handoff always uses critical priority`() {
        for (priority in ApmPriority.values()) {
            assertEquals(ApmPriority.CRITICAL, criticalHandoffPriority(priority))
        }
    }
}
