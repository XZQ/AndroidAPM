package com.apm.storage

/**
 * FileEventStore 文件重写节奏控制器。
 * 仅按新增 append 次数触发重写，避免缓冲区打满后每次都整文件重写。
 */
internal class FileRewriteScheduler(
    /** 重写间隔：每累计追加多少次触发一次文件裁剪。 */
    private val rewriteInterval: Int
) {

    /** 自上次 reset 以来的累计追加次数。 */
    private var appendCount: Int = 0

    /**
     * 记录一次追加，并返回当前是否需要重写文件。
     *
     * @return true 表示本次追加后应触发文件重写
     */
    fun onAppend(): Boolean {
        // 仅统计新的 append 次数，而不是使用当前缓冲区大小。
        appendCount++
        return appendCount % rewriteInterval == 0
    }

    /** 清空累计计数。 */
    fun reset() {
        appendCount = 0
    }
}
