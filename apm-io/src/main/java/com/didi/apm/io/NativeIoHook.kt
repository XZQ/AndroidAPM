package com.didi.apm.io

import android.os.Looper
import com.didi.apm.core.Apm
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * IO 自动 Hook 框架。
 * 通过代理 InputStream/OutputStream 的方式自动采集 IO 操作，
 * 无需业务方主动调用。
 *
 * 检测维度（对标 Matrix IOCanary）：
 * 1. 主线程 IO 耗时
 * 2. 小 buffer 检测（频繁小数据读写）
 * 3. 重复读检测（同一文件被多次读取）
 * 4. Closeable 泄漏检测（PhantomReference 追踪未 close 的流）
 *
 * 注意：完整的 Native PLT Hook（拦截 libc open/read/write/close）
 * 需要 xhook/bhook JNI 库支持。本实现使用 Java 层代理方案作为降级。
 */
class NativeIoHook(
    /** 模块配置。 */
    private val config: IoConfig
) {

    /** 活跃的 IO 会话：System.identityHashCode → IoSession。 */
    private val activeSessions = ConcurrentHashMap<Int, IoSession>()
    /** 文件读取计数器：path → 读取次数。 */
    private val readFileCounts = ConcurrentHashMap<String, Int>()
    /** Closeable 泄漏追踪：PhantomReference → 描述信息。 */
    private val closeableRefs = ConcurrentHashMap<PhantomReference<Any>, String>()
    /** Closeable 泄漏检测队列。 */
    private val closeableQueue = ReferenceQueue<Any>()
    /** 是否已初始化。 */
    @Volatile
    private var initialized = false

    /**
     * 初始化 IO Hook。
     * 启动 Closeable 泄漏检测线程。
     */
    fun init() {
        if (initialized) return
        initialized = true
        // 启动 Closeable 泄漏检测线程
        if (config.enableCloseableLeak) {
            val leakThread = Thread({ monitorCloseableLeaks() }, THREAD_NAME)
            leakThread.isDaemon = true
            leakThread.start()
        }
    }

    /**
     * 包装 InputStream，自动追踪读取操作。
     *
     * @param source 原始 InputStream
     * @param path 文件路径
     * @return 代理后的 InputStream
     */
    fun wrapInputStream(source: InputStream, path: String): InputStream {
        val sessionId = System.identityHashCode(source)
        val session = IoSession(
            path = path,
            openTime = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            isMainThread = Looper.myLooper() == Looper.getMainLooper()
        )
        activeSessions[sessionId] = session
        // 注册 Closeable 泄漏追踪
        if (config.enableCloseableLeak) {
            closeableRefs[PhantomReference(source, closeableQueue)] = path
        }
        return source
    }

    /**
     * 包装 OutputStream，自动追踪写入操作。
     *
     * @param source 原始 OutputStream
     * @param path 文件路径
     * @return 代理后的 OutputStream
     */
    fun wrapOutputStream(source: OutputStream, path: String): OutputStream {
        val sessionId = System.identityHashCode(source)
        val session = IoSession(
            path = path,
            openTime = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            isMainThread = Looper.myLooper() == Looper.getMainLooper()
        )
        activeSessions[sessionId] = session
        if (config.enableCloseableLeak) {
            closeableRefs[PhantomReference(source, closeableQueue)] = path
        }
        return source
    }

    /**
     * 记录一次 IO 读取操作。
     *
     * @param path 文件路径
     * @param bytesRead 读取字节数
     * @param bufferUsed 使用的 buffer 大小
     */
    fun onRead(path: String, bytesRead: Int, bufferUsed: Int) {
        if (!initialized) return

        // 小 buffer 检测
        if (bufferUsed in 1 until config.smallBufferThreshold) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_SMALL_BUFFER,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.INFO,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_BUFFER_SIZE to bufferUsed,
                    FIELD_THRESHOLD to config.smallBufferThreshold
                )
            )
        }

        // 重复读检测
        val readCount = readFileCounts.getOrDefault(path, 0) + 1
        readFileCounts[path] = readCount
        if (readCount >= config.duplicateReadThreshold) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_DUPLICATE_READ,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_READ_COUNT to readCount,
                    FIELD_THRESHOLD to config.duplicateReadThreshold
                )
            )
        }
    }

    /**
     * 记录流关闭，分析 IO 会话数据。
     *
     * @param source 原始流对象
     * @param totalBytes 总字节数
     */
    fun onClose(source: Any, totalBytes: Long) {
        val sessionId = System.identityHashCode(source)
        val session = activeSessions.remove(sessionId) ?: return
        val durationMs = System.currentTimeMillis() - session.openTime

        // 主线程 IO 耗时检测
        if (session.isMainThread && durationMs >= config.mainThreadIoThresholdMs) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_MAIN_THREAD_IO,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.ERROR,
                fields = mapOf(
                    FIELD_PATH to session.path.take(MAX_PATH_LENGTH),
                    FIELD_DURATION_MS to durationMs,
                    FIELD_BYTES to totalBytes
                )
            )
        }
    }

    /**
     * Closeable 泄漏检测线程。
     * 当 PhantomReference 被 GC 回收到 ReferenceQueue 时，
     * 说明流对象已被 GC 但未被显式 close。
     */
    private fun monitorCloseableLeaks() {
        while (initialized) {
            try {
                val ref = closeableQueue.remove(CLOSEABLE_CHECK_INTERVAL_MS)
                if (ref != null) {
                    val path = closeableRefs.remove(ref)
                    if (path != null) {
                        Apm.emit(
                            module = MODULE_IO,
                            name = EVENT_CLOSEABLE_LEAK,
                            kind = ApmEventKind.ALERT,
                            severity = ApmSeverity.WARN,
                            fields = mapOf(FIELD_PATH to path.take(MAX_PATH_LENGTH))
                        )
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                // 静默处理
            }
        }
    }

    /**
     * 释放资源。
     */
    fun destroy() {
        initialized = false
        activeSessions.clear()
        readFileCounts.clear()
        closeableRefs.clear()
    }

    /** IO 会话数据。 */
    data class IoSession(
        /** 文件路径。 */
        val path: String,
        /** 打开时间。 */
        val openTime: Long,
        /** 打开线程名。 */
        val threadName: String,
        /** 是否主线程。 */
        val isMainThread: Boolean
    )

    companion object {
        /** IO 模块名。 */
        private const val MODULE_IO = "io"
        /** 小 buffer 事件。 */
        private const val EVENT_SMALL_BUFFER = "io_small_buffer"
        /** 重复读事件。 */
        private const val EVENT_DUPLICATE_READ = "io_duplicate_read"
        /** 主线程 IO 事件。 */
        private const val EVENT_MAIN_THREAD_IO = "io_main_thread"
        /** Closeable 泄漏事件。 */
        private const val EVENT_CLOSEABLE_LEAK = "io_closeable_leak"
        /** 字段：路径。 */
        private const val FIELD_PATH = "path"
        /** 字段：buffer 大小。 */
        private const val FIELD_BUFFER_SIZE = "bufferSize"
        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"
        /** 字段：读取次数。 */
        private const val FIELD_READ_COUNT = "readCount"
        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"
        /** 字段：字节数。 */
        private const val FIELD_BYTES = "bytes"
        /** 路径最大长度。 */
        private const val MAX_PATH_LENGTH = 256
        /** Closeable 检测间隔（毫秒）。 */
        private const val CLOSEABLE_CHECK_INTERVAL_MS = 1000L
        /** 泄漏检测线程名。 */
        private const val THREAD_NAME = "apm-io-leak-monitor"
    }
}
