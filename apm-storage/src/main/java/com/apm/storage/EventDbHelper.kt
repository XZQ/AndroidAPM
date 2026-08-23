package com.apm.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * APM 事件数据库助手。
 *
 * 管理 SQLite 数据库的创建和版本升级。
 * 数据库名：apm_events.db，单表 events。
 *
 * WAL 模式启用以提高并发读写性能。
 */
class EventDbHelper(context: Context, name: String = DATABASE_NAME, version: Int = DATABASE_VERSION) : SQLiteOpenHelper(context, name, null, version) {

    init {
        // WAL 模式：读不阻塞写，写不阻塞读。
        // 必须使用官方 API 而非 execSQL("PRAGMA journal_mode=WAL")——
        // 该 PRAGMA 会返回结果行，用 execSQL 执行在现代 Android 上直接抛
        // SQLiteException（"Queries can be performed using query or rawQuery only"）
        setWriteAheadLoggingEnabled(true)
    }

    /**
     * 创建数据库表。
     * events 表存储所有待上报的 APM 事件。
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_TABLE)
        createIndexes(db)
    }

    /**
     * 数据库升级。
     * Version 2 durable rows are migrated additively so pending telemetry is
     * never discarded when event identity and claim ownership are introduced.
     * Version 4 only adds the claim-order index; rows and existing columns are
     * untouched.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < DATABASE_VERSION_OUTBOX) {
            // Version 1 did not retain a reversible event payload, so its rows
            // cannot participate in acknowledged replay safely.
            db.execSQL(SQL_DROP_TABLE)
            onCreate(db)
            return
        }
        if (oldVersion < DATABASE_VERSION_LEASES && newVersion >= DATABASE_VERSION_LEASES) {
            // SQLite requires a default while adding a NOT NULL column to a
            // populated table. Every legacy row receives a deterministic ID.
            db.execSQL(SQL_ADD_EVENT_ID)
            db.execSQL(SQL_ADD_LEASE_OWNER)
            db.execSQL(SQL_ADD_LEASE_EXPIRY)
            db.execSQL(SQL_BACKFILL_EVENT_ID)
            createIndexes(db)
        }
        if (oldVersion < DATABASE_VERSION_CLAIM_ORDER && newVersion >= DATABASE_VERSION_CLAIM_ORDER) {
            // 纯加性索引升级：v2 直升 v4 时上面的 createIndexes 已用 IF NOT EXISTS
            // 创建过全部索引，这里对 v3 升 v4 的安装单独补建 claim 排序索引。
            db.execSQL(SQL_CREATE_CLAIM_ORDER_INDEX)
        }
    }

    /** Creates all indexes required by upload ordering and lease lookup. */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_PRIORITY_INDEX)
        db.execSQL(SQL_CREATE_EVENT_ID_INDEX)
        db.execSQL(SQL_CREATE_AVAILABILITY_INDEX)
        db.execSQL(SQL_CREATE_CLAIM_ORDER_INDEX)
    }


    companion object {
        /** 数据库文件名。 */
        private const val DATABASE_NAME = "apm_events.db"

        /** 数据库版本。 */
        private const val DATABASE_VERSION = 4

        /** First schema version that contains the durable payload column. */
        private const val DATABASE_VERSION_OUTBOX = 2

        /** First schema version that supports claim ownership and expiry. */
        private const val DATABASE_VERSION_LEASES = 3

        /** First schema version with the dedicated claim/read ordering index. */
        private const val DATABASE_VERSION_CLAIM_ORDER = 4

        /** 创建 events 表。 */
        private const val SQL_CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS events (
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

        /** 创建优先级+时间戳联合索引，用于按优先级取出和水位线淘汰。 */
        private const val SQL_CREATE_PRIORITY_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_priority_ts
            ON events(priority ASC, timestamp ASC)
        """

        /** Enforces one durable row per stable event identity. */
        private const val SQL_CREATE_EVENT_ID_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_event_id
            ON events(event_id)
        """

        /** Accelerates available-row scans performed by upload workers. */
        private const val SQL_CREATE_AVAILABILITY_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_lease_expiry_priority_ts
            ON events(lease_expires_at, priority DESC, timestamp ASC)
        """

        /**
         * Claim/readPending 的精确排序索引。
         * `ORDER BY priority DESC, timestamp ASC` 是混合方向排序，既不是
         * idx_priority_ts(ASC,ASC) 的正向也不是其反向扫描，历史上只能全表扫描
         * 加临时 B-tree 排序；该索引让 claim 每轮循环沿索引顺序扫描并在满足
         * limit 时提前停止。
         */
        private const val SQL_CREATE_CLAIM_ORDER_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_priority_desc_ts
            ON events(priority DESC, timestamp ASC)
        """

        /** Adds stable event identity to schema version 2. */
        private const val SQL_ADD_EVENT_ID =
            "ALTER TABLE events ADD COLUMN event_id TEXT NOT NULL DEFAULT ''"

        /** Adds nullable claim ownership to schema version 2. */
        private const val SQL_ADD_LEASE_OWNER =
            "ALTER TABLE events ADD COLUMN lease_owner TEXT"

        /** Adds claim expiry to schema version 2. */
        private const val SQL_ADD_LEASE_EXPIRY =
            "ALTER TABLE events ADD COLUMN lease_expires_at INTEGER NOT NULL DEFAULT 0"

        /** Assigns deterministic install-local identities to existing rows. */
        private const val SQL_BACKFILL_EVENT_ID =
            "UPDATE events SET event_id = 'legacy-' || id WHERE event_id = ''"

        /** Drops the legacy events table during the version 1 migration. */
        private const val SQL_DROP_TABLE = "DROP TABLE IF EXISTS events"
    }
}
