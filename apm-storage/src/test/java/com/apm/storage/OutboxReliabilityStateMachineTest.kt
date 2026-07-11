package com.apm.storage

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Fixed-seed state-machine coverage for durable outbox ownership invariants. */
@RunWith(RobolectricTestRunner::class)
class OutboxReliabilityStateMachineTest {

    /** Exercises mixed duplicate, claim, acknowledgement, failure, release, and expiry operations. */
    @Test
    fun `fixed seed operations preserve durable ownership invariants`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "outbox-state-${System.nanoTime()}.db"
        val store = SQLiteEventStore(EventDbHelper(context, databaseName), maxEvents = MAX_MODEL_ROWS)
        val random = Random(STATE_MACHINE_SEED)
        val model = linkedMapOf<String, ModelRow>()
        var sequence = 0L
        var nowMs = INITIAL_NOW_MS
        try {
            repeat(OPERATION_COUNT) {
                val operation = if (model.isEmpty()) OP_APPEND_UNIQUE else random.nextInt(OPERATION_VARIANTS)
                when (operation) {
                    OP_APPEND_UNIQUE -> {
                        val eventId = "state-${sequence++}"
                        val event = event(eventId, sequence)
                        store.append(event)
                        model[eventId] = ModelRow(eventId, event.timestamp)
                    }
                    OP_APPEND_DUPLICATE -> {
                        val existing = model.values.random(random)
                        store.append(event(existing.eventId, sequence++))
                    }
                    OP_CLAIM -> {
                        val owner = randomOwner(random)
                        val expected = model.values
                            .filter { row -> row.owner == null || row.expiresAtMs <= nowMs }
                            .sortedBy(ModelRow::timestampMs)
                            .take(CLAIM_LIMIT)
                            .map(ModelRow::eventId)
                        val claimed = store.claimPending(owner, CLAIM_LIMIT, nowMs, LEASE_DURATION_MS)
                        assertEquals(expected, claimed.map { row -> row.event.eventId })
                        for (eventId in expected) {
                            model.getValue(eventId).owner = owner
                            model.getValue(eventId).expiresAtMs = nowMs + LEASE_DURATION_MS
                        }
                    }
                    OP_ACKNOWLEDGE -> {
                        val owner = randomOwner(random)
                        val actualRows = store.readPending(MAX_MODEL_ROWS)
                        val selected = actualRows.shuffled(random).take(MUTATION_LIMIT)
                        val removable = selected.map { row -> row.event.eventId }
                            .filter { eventId -> model.getValue(eventId).owner == owner }
                        assertEquals(removable.size, store.acknowledgeClaim(owner, selected.map(PendingEvent::id)))
                        removable.forEach(model::remove)
                    }
                    OP_FAIL -> {
                        val owner = randomOwner(random)
                        val actualRows = store.readPending(MAX_MODEL_ROWS)
                        val selected = actualRows.shuffled(random).take(MUTATION_LIMIT)
                        store.failClaim(owner, selected.map(PendingEvent::id))
                        for (eventId in selected.map { row -> row.event.eventId }) {
                            val row = model.getValue(eventId)
                            if (row.owner == owner) {
                                row.owner = null
                                row.expiresAtMs = NO_EXPIRY
                                row.retryCount += 1
                            }
                        }
                    }
                    OP_RELEASE -> {
                        val owner = randomOwner(random)
                        val expected = model.values.count { row -> row.owner == owner }
                        assertEquals(expected, store.releaseClaims(owner))
                        model.values.filter { row -> row.owner == owner }.forEach { row ->
                            row.owner = null
                            row.expiresAtMs = NO_EXPIRY
                        }
                    }
                    OP_ADVANCE_CLOCK -> nowMs += random.nextLong(MAX_CLOCK_ADVANCE_MS + 1L)
                }
                assertModel(store, model)
            }
        } finally {
            store.close()
        }
    }

    /** Creates one deterministic event with a caller-controlled stable identity. */
    private fun event(eventId: String, sequence: Long): ApmEvent {
        return ApmEvent(
            module = "state-machine",
            name = eventId,
            priority = ApmPriority.NORMAL,
            timestamp = sequence,
            eventId = eventId
        )
    }

    /** Returns one of the two competing durable owner identities. */
    private fun randomOwner(random: Random): String = if (random.nextBoolean()) OWNER_A else OWNER_B

    /** Compares externally observable store state with the deterministic in-memory model. */
    private fun assertModel(store: SQLiteEventStore, model: Map<String, ModelRow>) {
        val actual = store.readPending(MAX_MODEL_ROWS)
        val actualIds = actual.map { row -> row.event.eventId }
        assertEquals(actualIds.size, actualIds.toSet().size)
        assertEquals(model.keys, actualIds.toSet())
        assertEquals(model.size, store.pendingCount())
        val actualRetries = actual.associate { row -> row.event.eventId to row.retryCount }
        assertEquals(model.mapValues { (_, row) -> row.retryCount }, actualRetries)
        assertTrue(actual.all { row -> row.event.eventId.isNotBlank() })
    }

    /** Minimal model fields needed to predict owner-aware store mutations. */
    private data class ModelRow(
        /** Stable event identity. */
        val eventId: String,
        /** Ordering timestamp persisted with the event. */
        val timestampMs: Long,
        /** Current lease owner, or null when available. */
        var owner: String? = null,
        /** Wall-clock lease expiry. */
        var expiresAtMs: Long = NO_EXPIRY,
        /** Durable retry count. */
        var retryCount: Int = 0
    )

    private companion object {
        /** Reproducible randomized-operation seed. */
        private const val STATE_MACHINE_SEED = 0x0A_92_02_6
        /** Number of mixed operations in one deterministic run. */
        private const val OPERATION_COUNT = 250
        /** Capacity kept above generated rows so this test isolates ownership rather than eviction. */
        private const val MAX_MODEL_ROWS = 1_000
        /** Initial fake wall clock. */
        private const val INITIAL_NOW_MS = 10_000L
        /** Fixed claim lease duration. */
        private const val LEASE_DURATION_MS = 50L
        /** Maximum fake-clock increment per operation. */
        private const val MAX_CLOCK_ADVANCE_MS = 100L
        /** Maximum rows selected by one claim. */
        private const val CLAIM_LIMIT = 3
        /** Maximum rows selected by one owner mutation. */
        private const val MUTATION_LIMIT = 4
        /** Unowned expiry representation in the model. */
        private const val NO_EXPIRY = 0L
        /** First competing owner. */
        private const val OWNER_A = "owner-a"
        /** Second competing owner. */
        private const val OWNER_B = "owner-b"
        /** Number of operation variants. */
        private const val OPERATION_VARIANTS = 7
        /** Append a unique event. */
        private const val OP_APPEND_UNIQUE = 0
        /** Append a duplicate event identity. */
        private const val OP_APPEND_DUPLICATE = 1
        /** Claim available rows. */
        private const val OP_CLAIM = 2
        /** Acknowledge selected rows. */
        private const val OP_ACKNOWLEDGE = 3
        /** Fail selected rows. */
        private const val OP_FAIL = 4
        /** Release one owner's leases. */
        private const val OP_RELEASE = 5
        /** Advance the fake wall clock. */
        private const val OP_ADVANCE_CLOCK = 6
    }
}
