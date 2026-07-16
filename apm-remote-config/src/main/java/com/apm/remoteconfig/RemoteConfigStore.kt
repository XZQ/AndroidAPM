package com.apm.remoteconfig

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/** Durable store for the last verified document and its rollback/time metadata. */
internal interface RemoteConfigStore {

    /** Returns the complete cached state, or an empty rollback floor on first use. */
    fun read(): CachedRemoteConfig

    /** Atomically persists one complete cache state. */
    fun write(value: CachedRemoteConfig): Boolean
}

/** SharedPreferences implementation using synchronous commit on the refresh worker. */
internal class SharedPreferencesRemoteConfigStore(context: Context) : RemoteConfigStore {

    /** App-private preferences detached from Activity lifecycles. */
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** Reads a self-consistent snapshot; missing fields degrade to an inactive empty cache. */
    override fun read(): CachedRemoteConfig {
        return CachedRemoteConfig(
            rawJson = preferences.getString(KEY_RAW_JSON, null),
            etag = preferences.getString(KEY_ETAG, null),
            highestRevision = preferences.getLong(KEY_HIGHEST_REVISION, 0L),
            active = preferences.getBoolean(KEY_ACTIVE, false),
            serverTimeAtReceiptMs = preferences.getLong(KEY_SERVER_TIME, 0L),
            elapsedRealtimeAtReceiptMs = preferences.getLong(KEY_ELAPSED_TIME, 0L)
        )
    }

    /** Commits metadata and body together so process death cannot publish a partial revision. */
    override fun write(value: CachedRemoteConfig): Boolean {
        val editor = preferences.edit()
            .putLong(KEY_HIGHEST_REVISION, value.highestRevision)
            .putBoolean(KEY_ACTIVE, value.active)
            .putLong(KEY_SERVER_TIME, value.serverTimeAtReceiptMs)
            .putLong(KEY_ELAPSED_TIME, value.elapsedRealtimeAtReceiptMs)
        if (value.rawJson == null) {
            editor.remove(KEY_RAW_JSON)
        } else {
            editor.putString(KEY_RAW_JSON, value.rawJson)
        }
        if (value.etag == null) {
            editor.remove(KEY_ETAG)
        } else {
            editor.putString(KEY_ETAG, value.etag)
        }
        return editor.commit()
    }

    companion object {
        /** Private cache file name. */
        private const val PREFERENCES_NAME = "androidapm_signed_remote_config"

        /** Cache field names. */
        private const val KEY_RAW_JSON = "raw_json"
        private const val KEY_ETAG = "etag"
        private const val KEY_HIGHEST_REVISION = "highest_revision"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SERVER_TIME = "server_time_at_receipt_ms"
        private const val KEY_ELAPSED_TIME = "elapsed_realtime_at_receipt_ms"
    }
}

/** Supplies wall and monotonic clocks for trusted-time anchoring and deterministic tests. */
internal interface RemoteConfigClock {

    /** Returns current Unix epoch milliseconds. */
    fun wallTimeMs(): Long

    /** Returns milliseconds since boot, including device sleep. */
    fun elapsedRealtimeMs(): Long
}

/** Android clock implementation; monotonic time protects expiry checks until the next reboot. */
internal object AndroidRemoteConfigClock : RemoteConfigClock {

    /** Delegates to the system wall clock. */
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    /** Delegates to Android's sleep-inclusive monotonic clock. */
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
