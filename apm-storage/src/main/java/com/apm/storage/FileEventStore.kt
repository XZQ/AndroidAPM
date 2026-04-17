package com.apm.storage

import android.content.Context
import com.apm.model.ApmEvent
import com.apm.model.toLineProtocol
import java.io.File
import java.util.ArrayDeque

/**
 * 基于文件的事件存储实现。
 *
 * 使用内存 ArrayDeque 作为环形缓冲区，定期将数据刷到本地文件。
 * 延迟初始化：文件读取在首次 append/read 时执行，避免主线程 I/O。
 *
 * 线程安全：所有公开方法使用 @Synchronized 保证串行访问。
 */
class FileEventStore(
    context: Context,
    /** 环形缓冲区最大行数。超出后丢弃最旧的记录。 */
    private val maxLines: Int = DEFAULT_MAX_LINES
) : EventStore {

    /** 事件存储文件路径。 */
    private val eventFile = File(context.filesDir, FILE_PATH)

    /** 内存环形缓冲区，保存最近的事件。 */
    private val recentLines = ArrayDeque<String>(maxLines)

    /** 是否已完成文件加载。 */
    @Volatile
    private var initialized = false

    /**
     * 延迟初始化：首次访问时从文件加载历史数据。
     * 使用 double-check + synchronized 保证只加载一次。
     */
    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // 确保目录和文件存在
            eventFile.parentFile?.mkdirs()
            if (!eventFile.exists()) {
                eventFile.createNewFile()
            }
            // 从文件尾部加载最近 maxLines 行到内存
            eventFile.takeIf(File::exists)
                ?.readLines()
                ?.takeLast(maxLines)
                ?.forEach(recentLines::addLast)
            initialized = true
        }
    }

    /**
     * 追加一条事件。执行流程：
     * 1. 确保已从文件加载历史数据
     * 2. 序列化为 line protocol
     * 3. 写入文件末尾
     * 4. 加入内存缓冲区，超限时丢弃最旧的
     * 5. 每 50 条重写文件，防止文件无限膨胀
     */
    @Synchronized
    override fun append(event: ApmEvent) {
        ensureInit()
        val line = event.toLineProtocol()
        // 追加写入文件
        eventFile.appendText(line + LINE_BREAK)
        recentLines.addLast(line)
        // 环形缓冲区：超出容量时移除最旧的
        while (recentLines.size > maxLines) {
            recentLines.removeFirst()
        }
        // 定期重写文件，裁剪掉已被淘汰的旧数据
        if (recentLines.size % REWRITE_INTERVAL == 0) {
            rewriteWithRecentLines()
        }
    }

    /**
     * 读取最近的事件。返回最新在前。
     */
    @Synchronized
    override fun readRecent(limit: Int): List<String> {
        ensureInit()
        if (limit <= 0 || recentLines.isEmpty()) return emptyList()
        return recentLines.toList().takeLast(limit).reversed()
    }

    /** 清除所有数据：内存 + 文件。 */
    @Synchronized
    override fun clear() {
        ensureInit()
        eventFile.writeText("")
        recentLines.clear()
    }

    /**
     * 将内存缓冲区中的数据完整重写到文件。
     * 用于定期裁剪文件大小。
     */
    private fun rewriteWithRecentLines() {
        eventFile.writeText(recentLines.joinToString(separator = LINE_BREAK, postfix = LINE_BREAK))
    }

    companion object {
        /** 默认环形缓冲区容量。 */
        private const val DEFAULT_MAX_LINES = 500

        /** 文件存储路径。 */
        private const val FILE_PATH = "apm/events.log"

        /** 触发文件重写的间隔（每 N 条追加重写一次）。 */
        private const val REWRITE_INTERVAL = 50

        /** 行分隔符。 */
        private const val LINE_BREAK = "\n"
    }
}
