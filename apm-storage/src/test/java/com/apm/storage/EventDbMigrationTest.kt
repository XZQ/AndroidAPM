package com.apm.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Additive database migration tests for durable identity and lease ownership. */
@RunWith(RobolectricTestRunner::class)
class EventDbMigrationTest {

    /** Version 2 rows remain readable and receive stable install-local identities. */
    @Test
    fun `version two row migrates without data loss`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "migration-${System.nanoTime()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL(VERSION_TWO_TABLE)
            database.version = VERSION_TWO
            database.insert("events", null, legacyRow())
        }

        val store = SQLiteEventStore(EventDbHelper(context, databaseName))
        val first = store.claimPending("worker-a", 1, 1_000L, 500L).single()
        store.failClaim("worker-a", listOf(first.id))
        val second = store.claimPending("worker-b", 1, 1_001L, 500L).single()

        assertEquals("legacy", first.event.name)
        assertFalse(first.event.eventId.isBlank())
        assertEquals(first.event.eventId, second.event.eventId)
        store.close()
    }

    /** Version 3 rows survive the version 4 additive index migration and claim in order. */
    @Test
    fun `version three rows migrate to version four with claim order index`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "migration-v4-${System.nanoTime()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val encodedLow = com.apm.model.ApmEventCodec.encode(lowPriorityEvent)
        val encodedHigh = com.apm.model.ApmEventCodec.encode(highPriorityEvent)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL(VERSION_THREE_TABLE)
            database.execSQL(VERSION_THREE_PRIORITY_INDEX)
            database.execSQL(VERSION_THREE_EVENT_ID_INDEX)
            database.execSQL(VERSION_THREE_AVAILABILITY_INDEX)
            // 先插入低优先级（时间更早），再插入高优先级，验证迁移后按 priority DESC 取出
            database.insert("events", null, versionThreeRow(encodedLow, lowPriorityEvent))
            database.insert("events", null, versionThreeRow(encodedHigh, highPriorityEvent))
            database.version = VERSION_THREE
        }

        val store = SQLiteEventStore(EventDbHelper(context, databaseName))

        // 迁移后 claim 排序索引存在且行完整保留
        EventDbHelper(context, databaseName).readableDatabase.use { database ->
            val indexCursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(CLAIM_ORDER_INDEX_NAME)
            )
            indexCursor.use { cursor ->
                assertTrue("claim order index must exist after migration", cursor.moveToFirst())
            }
        }
        val claimed = store.claimPending("worker-v4", 2, 1_000L, 500L)
        assertEquals(listOf("high", "low"), claimed.map { it.event.name })
        assertEquals(2, store.pendingCount())
        store.close()
    }

    /** Creates one complete row matching schema version 3. */
    private fun versionThreeRow(payload: ByteArray, event: com.apm.model.ApmEvent): ContentValues =
        ContentValues().apply {
            // claim 按 *存储的* priority 列排序，必须写入事件真实映射值
            put("priority", StoragePriorityMapper.priorityOf(event))
            put("module", event.module)
            put("name", event.name)
            put("severity", "INFO")
            put("data", "")
            put("payload", payload)
            put("event_id", event.eventId)
            put("timestamp", event.timestamp)
            put("retry_count", 0)
            putNull("lease_owner")
            put("lease_expires_at", 0L)
        }

    /** Creates one complete row matching schema version 2. */
    private fun legacyRow(): ContentValues = ContentValues().apply {
        put("priority", 1)
        put("module", "core")
        put("name", "legacy")
        put("severity", "INFO")
        put("data", "")
        put("payload", versionOnePayload())
        put("timestamp", 123L)
        put("retry_count", 0)
    }

    /** Creates a valid durable codec version-1 payload. */
    private fun versionOnePayload(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(1)
            output.writeLong(123L)
            listOf("core", "legacy", "METRIC", "INFO", "NORMAL", "process", "thread").forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            output.writeBoolean(false)
            output.writeByte(0)
            repeat(3) { output.writeInt(0) }
        }
        return buffer.toByteArray()
    }

    /** Schema used by published client version 1.1. */
    private companion object {
        /** Previous database version. */
        private const val VERSION_TWO = 2
        /** Exact previous table definition. */
        private const val VERSION_TWO_TABLE = """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                priority INTEGER NOT NULL DEFAULT 1,
                module TEXT NOT NULL,
                name TEXT NOT NULL,
                severity TEXT NOT NULL,
                data TEXT NOT NULL,
                payload BLOB NOT NULL,
                timestamp INTEGER NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0
            )
        """

        /** Schema version immediately before the claim-order index migration. */
        private const val VERSION_THREE = 3

        /** Exact schema-version-3 table definition including lease columns. */
        private const val VERSION_THREE_TABLE = """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                priority INTEGER NOT NULL DEFAULT 1,
                module TEXT NOT NULL,
                name TEXT NOT NULL,
                severity TEXT NOT NULL,
                data TEXT NOT NULL,
                payload BLOB NOT NULL,
                event_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                lease_owner TEXT,
                lease_expires_at INTEGER NOT NULL DEFAULT 0
            )
        """

        /** Historical version-3 priority index. */
        private const val VERSION_THREE_PRIORITY_INDEX =
            "CREATE INDEX idx_priority_ts ON events(priority ASC, timestamp ASC)"

        /** Historical version-3 unique event identity index. */
        private const val VERSION_THREE_EVENT_ID_INDEX =
            "CREATE UNIQUE INDEX idx_event_id ON events(event_id)"

        /** Historical version-3 lease availability index. */
        private const val VERSION_THREE_AVAILABILITY_INDEX =
            "CREATE INDEX idx_lease_expiry_priority_ts ON events(lease_expires_at, priority DESC, timestamp ASC)"

        /** Claim-order index introduced by schema version 4. */
        private const val CLAIM_ORDER_INDEX_NAME = "idx_priority_desc_ts"

        /** Migration fixture: older LOW event claimed after the newer HIGH one. */
        private val lowPriorityEvent = com.apm.model.ApmEvent(
            module = "migration",
            name = "low",
            priority = com.apm.model.ApmPriority.LOW,
            timestamp = 100L,
            eventId = "migration-low"
        )

        /** Migration fixture: newer HIGH event claimed first. */
        private val highPriorityEvent = com.apm.model.ApmEvent(
            module = "migration",
            name = "high",
            priority = com.apm.model.ApmPriority.HIGH,
            timestamp = 200L,
            eventId = "migration-high"
        )
    }
}
