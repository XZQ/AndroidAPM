package com.apm.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics configuration contract tests.
 */
class DiagnosticsConfigTest {

    /** Default diagnostics settings must stay enabled and resource-bounded. */
    @Test
    fun `defaults are enabled and bounded`() {
        val config = DiagnosticsConfig()

        assertTrue(config.enabled)
        assertEquals(200, config.memoryRecordLimit)
        assertEquals(4L * 1024L * 1024L, config.memoryByteLimit)
        assertEquals(256, config.writerQueueCapacity)
        assertEquals(4L * 1024L * 1024L, config.writerQueueByteLimit)
        assertEquals(512L * 1024L, config.maxFileBytes)
        assertEquals(3, config.retainedFileCount)
        assertTrue(config.includeStackTraces)
    }

    /** Memory limits above the documented hard bound must be rejected. */
    @Test(expected = IllegalArgumentException::class)
    fun `memory limit above hard bound is rejected`() {
        DiagnosticsConfig(memoryRecordLimit = 2_001)
    }

    /** A diagnostics journal must retain at least one file segment. */
    @Test(expected = IllegalArgumentException::class)
    fun `non positive retained file count is rejected`() {
        DiagnosticsConfig(retainedFileCount = 0)
    }

    /** Variable-sized buffers must reject byte budgets above the documented hard bound. */
    @Test(expected = IllegalArgumentException::class)
    fun `memory byte limit above hard bound is rejected`() {
        DiagnosticsConfig(memoryByteLimit = 8L * 1024L * 1024L + 1L)
    }
}
