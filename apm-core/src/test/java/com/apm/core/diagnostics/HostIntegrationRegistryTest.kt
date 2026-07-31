package com.apm.core.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Deterministic tests for value-free host integration readiness evidence. */
class HostIntegrationRegistryTest {

    /** Starts each case with an empty active session. */
    @Before
    fun setUp() {
        HostIntegrationRegistry.beginSession()
    }

    /** Prevents late callbacks from one case affecting another. */
    @After
    fun tearDown() {
        HostIntegrationRegistry.endSession()
    }

    /** Snapshot order and empty-session semantics remain stable. */
    @Test
    fun `fresh session exposes every point without claiming a missing integration`() {
        val snapshot = ApmDiagnostics.hostIntegrationSnapshot()

        assertEquals(HostIntegrationPoint.entries, snapshot.integrations.map(HostIntegrationStatus::point))
        assertTrue(snapshot.capturedAtMs > 0L)
        snapshot.integrations.forEach { status ->
            assertFalse(status.moduleActive)
            assertEquals(0, status.activeRegistrations)
            assertEquals(0L, status.observedSignals)
            assertNull(status.lastObservedAtMs)
            assertEquals(HostIntegrationState.MODULE_INACTIVE, status.state)
        }
    }

    /** Module, registration, and observation evidence produce distinct reader-facing states. */
    @Test
    fun `state preserves module registration and observation distinctions`() {
        HostIntegrationRegistry.setModuleActive(HostIntegrationPoint.WEBVIEW, true)
        assertPointState(HostIntegrationPoint.WEBVIEW, HostIntegrationState.NO_RUNTIME_EVIDENCE)

        HostIntegrationRegistry.setActiveRegistrations(HostIntegrationPoint.WEBVIEW, 2)
        assertPointState(HostIntegrationPoint.WEBVIEW, HostIntegrationState.REGISTRATION_ACTIVE)

        HostIntegrationRegistry.recordObservation(HostIntegrationPoint.WEBVIEW)
        val observed = point(HostIntegrationPoint.WEBVIEW)
        assertEquals(HostIntegrationState.REGISTRATION_ACTIVE_AND_OBSERVED, observed.state)
        assertEquals(2, observed.activeRegistrations)
        assertEquals(1L, observed.observedSignals)
        assertNotNull(observed.lastObservedAtMs)

        HostIntegrationRegistry.setActiveRegistrations(HostIntegrationPoint.WEBVIEW, 0)
        assertPointState(HostIntegrationPoint.WEBVIEW, HostIntegrationState.OBSERVED)
    }

    /** Stopping a module clears live registrations but retains bounded support evidence. */
    @Test
    fun `module stop clears active registrations and retains observations`() {
        HostIntegrationRegistry.setModuleActive(HostIntegrationPoint.THREAD_POOL, true)
        HostIntegrationRegistry.setActiveRegistrations(HostIntegrationPoint.THREAD_POOL, 3)
        HostIntegrationRegistry.recordObservation(HostIntegrationPoint.THREAD_POOL)

        HostIntegrationRegistry.setModuleActive(HostIntegrationPoint.THREAD_POOL, false)

        val stopped = point(HostIntegrationPoint.THREAD_POOL)
        assertFalse(stopped.moduleActive)
        assertEquals(0, stopped.activeRegistrations)
        assertEquals(1L, stopped.observedSignals)
        assertEquals(HostIntegrationState.MODULE_INACTIVE, stopped.state)
    }

    /** Reinitialization removes stale counts and late callbacks after stop are ignored. */
    @Test
    fun `session boundaries reject late signals and reset stale evidence`() {
        HostIntegrationRegistry.setModuleActive(HostIntegrationPoint.NETWORK, true)
        HostIntegrationRegistry.recordObservation(HostIntegrationPoint.NETWORK)
        HostIntegrationRegistry.endSession()
        HostIntegrationRegistry.recordObservation(HostIntegrationPoint.NETWORK)

        assertEquals(1L, point(HostIntegrationPoint.NETWORK).observedSignals)
        HostIntegrationRegistry.beginSession()

        val fresh = point(HostIntegrationPoint.NETWORK)
        assertEquals(0L, fresh.observedSignals)
        assertNull(fresh.lastObservedAtMs)
        assertEquals(HostIntegrationState.MODULE_INACTIVE, fresh.state)
    }

    /** Concurrent feature callbacks cannot lose integration evidence. */
    @Test
    fun `concurrent observations are counted exactly`() {
        HostIntegrationRegistry.setModuleActive(HostIntegrationPoint.SQLITE, true)
        val workers = 8
        val signalsPerWorker = 500
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        repeat(workers) {
            executor.execute {
                ready.countDown()
                start.await()
                repeat(signalsPerWorker) {
                    HostIntegrationRegistry.recordObservation(HostIntegrationPoint.SQLITE)
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        val status = point(HostIntegrationPoint.SQLITE)
        assertEquals((workers * signalsPerWorker).toLong(), status.observedSignals)
        assertEquals(HostIntegrationState.OBSERVED, status.state)
    }

    /** Returns one status by its stable enum key. */
    private fun point(point: HostIntegrationPoint): HostIntegrationStatus {
        return ApmDiagnostics.hostIntegrationSnapshot().integrations.single { it.point == point }
    }

    /** Asserts only the derived state for a concise transition test. */
    private fun assertPointState(point: HostIntegrationPoint, expected: HostIntegrationState) {
        assertEquals(expected, point(point).state)
    }
}
