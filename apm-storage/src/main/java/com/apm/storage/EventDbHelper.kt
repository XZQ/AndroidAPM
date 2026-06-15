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
class EventDbHelper(
    context: Context,
    /** 数据库名称。 */
    name: String = DATABASE_NAME,
    /** 数据库版本。 */
    version: Int = DATABASE_VERSION
) : SQLiteOpenHelper(context, name, null, version) {

    /**
     * 创建数据库表。
     * events 表存储所有待上报的 APM 事件。
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_TABLE)
        db.execSQL(SQL_CREATE_INDEX)
    }

    /**
     * 数据库升级。
     * 目前只有一个版本，无需升级逻辑。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < DATABASE_VERSION_OUTBOX) {
            // Version 1 did not retain a reversible event payload, so its rows
            // cannot participate in acknowledged replay safely.
            db.execSQL(SQL_DROP_TABLE)
            onCreate(db)
        }
    }

    /**
     * 数据库打开时启用 WAL 模式，提高并发读写性能。
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // WAL 模式：读不阻塞写，写不阻塞读
        if (!db.isReadOnly) {
            db.execSQL("PRAGMA journal_mode=WAL")
        }
    }

    companion object {
        /** 数据库文件名。 */
        private const val DATABASE_NAME = "apm_events.db"

        /** 数据库版本。 */
        private const val DATABASE_VERSION = 2

        /** First schema version that contains the durable payload column. */
        private const val DATABASE_VERSION_OUTBOX = 2

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
                timestamp INTEGER NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0
            )
        """

        /** 创建优先级+时间戳联合索引，用于按优先级取出和水位线淘汰。 */
        private const val SQL_CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_priority_ts
            ON events(priority ASC, timestamp ASC)
        """

        /** Drops the legacy events table during the version 1 migration. */
        private const val SQL_DROP_TABLE = "DROP TABLE IF EXISTS events"
    }
}
