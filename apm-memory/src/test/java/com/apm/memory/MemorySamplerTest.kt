package com.apm.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Robolectric smoke coverage for the complete snapshot collection boundary. */
@RunWith(RobolectricTestRunner::class)
class MemorySamplerTest {
    /** Snapshot collection preserves caller context and returns bounded metrics. */
    @Test
    fun `snapshot preserves scene and foreground state`() {
        val sampler = MemorySampler(RuntimeEnvironment.getApplication())

        val snapshot = sampler.buildSnapshot("Settings", foreground = false)

        assertEquals("Settings", snapshot.scene)
        assertEquals(false, snapshot.foreground)
        assertTrue(snapshot.javaHeapMaxMb >= 0L)
        assertTrue(snapshot.totalPssKb >= 0)
        assertTrue(snapshot.nativeHeapAllocatedKb >= 0L)
    }
}
