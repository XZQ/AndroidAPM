package com.apm.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }
}
