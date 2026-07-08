package com.apm.crash

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 基于 [android.app.ApplicationExitInfo] 的退出记录数据源。
 * 仅在 API 30+ 使用（由调用方做运行时版本守卫）。
 */
@TargetApi(Build.VERSION_CODES.R)
internal class AndroidExitInfoSource(private val context: Context) : ExitInfoSource {

    /**
     * 读取系统记录的历史进程退出原因。
     *
     * @param maxRecords 最大读取条数
     * @return 平台无关的退出记录快照列表
     */
    override fun latestExitRecords(maxRecords: Int): List<ExitRecord> {
        // ActivityManager 不可用时返回空（防御性处理）
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return emptyList()
        val exitInfos = activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            ALL_PIDS,
            maxRecords
        )
        return exitInfos.map { info ->
            ExitRecord(
                timestampMs = info.timestamp,
                reasonCode = info.reason,
                description = info.description,
                importance = info.importance,
                // 仅 ANR 记录携带系统 trace 流
                traceSupplier = if (info.reason == ExitReasonCollector.REASON_ANR) {
                    { runCatching { info.traceInputStream }.getOrNull() }
                } else {
                    null
                }
            )
        }
    }

    companion object {
        /** pid 过滤参数：0 表示读取所有历史 pid 的记录。 */
        private const val ALL_PIDS = 0
    }
}

/**
 * 基于 SharedPreferences 的退出记录处理位置存储。
 */
internal class PrefsExitTimestampStore(context: Context) : ExitTimestampStore {

    /** 退出采集专用的偏好存储。 */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取上次处理到的时间戳（毫秒），无记录返回 0。 */
    override fun lastProcessedMs(): Long = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)

    /**
     * 保存本次处理到的最新时间戳。
     *
     * @param value 时间戳（毫秒）
     */
    override fun saveLastProcessedMs(value: Long) {
        // apply 异步落盘即可，重复上报由时间戳比较兜底
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, value).apply()
    }

    companion object {
        /** SharedPreferences 文件名。 */
        private const val PREFS_NAME = "apm_crash_exit_info"

        /** 键：上次处理到的退出记录时间戳。 */
        private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    }
}
