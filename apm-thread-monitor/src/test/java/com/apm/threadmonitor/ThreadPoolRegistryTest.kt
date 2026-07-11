package com.apm.threadmonitor

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies explicit thread-pool registration and backlog snapshots. */
class ThreadPoolRegistryTest {
    /** Registered executors expose real queue and capacity values. */
    @Test
    fun `snapshot reports registered executor backlog`() {
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val registry = ThreadPoolRegistry()
        try {
            val blocker = java.util.concurrent.CountDownLatch(1)
            val started = java.util.concurrent.CountDownLatch(1)
            executor.execute {
                started.countDown()
                blocker.await()
            }
            assertTrue(started.await(5L, TimeUnit.SECONDS))
            executor.execute { Unit }
            registry.register("images", executor)

            val snapshot = registry.snapshots().single()

            assertEquals("images", snapshot.name)
            assertEquals(1, snapshot.maxPoolSize)
            assertEquals(1, snapshot.queuedTasks)
            blocker.countDown()
        } finally {
            executor.shutdownNow()
        }
    }

    /** Unregister removes the pool from future scans. */
    @Test
    fun `unregister removes executor`() {
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val registry = ThreadPoolRegistry()
        try {
            registry.register("network", executor)
            assertTrue(registry.unregister("network"))
            assertTrue(registry.snapshots().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
