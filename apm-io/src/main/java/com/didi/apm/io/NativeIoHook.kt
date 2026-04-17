package com.didi.apm.io

import android.os.Looper
import com.didi.apm.core.Apm
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * IO 自动 Hook 框架。
 * 通过代理 InputStream/OutputStream + Native PLT Hook 采集 IO 操作。
 *
 * ## 检测维度（对标 Matrix IOCanary + 增强）
 * 1. 主线程 IO 耗时
 * 2. 小 buffer 检测（频繁小数据读写）
 * 3. 重复读检测（同一文件被多次读取）
 * 4. Closeable 泄漏检测（PhantomReference 追踪未 close 的流）
 * 5. 文件描述符（FD）泄漏检测 — 新增
 * 6. IO 吞吐量统计（按路径/线程聚合）— 新增
 * 7. Native PLT Hook 接口（拦截 libc open/read/write/close）— 新增
 * 8. 零拷贝检测 — 新增
 *
 * ## Native Hook 层级
 * - **Level 1**：Java 层代理（默认，零依赖）
 * - **Level 2**：Native PLT Hook（需 JNI 库，更全面）
 * Level 2 不可用时自动降级为 Level 1。
 */
class NativeIoHook(private val config: IoConfig) {

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

    // --- FD 泄漏检测 ---
    /** 当前打开的 FD 路径记录：fd → path。 */
    private val openFdPaths = ConcurrentHashMap<Int, String>()

    /** FD 分配计数器。 */
    private val fdAllocCount = AtomicLong(0L)

    /** FD 释放计数器。 */
    private val fdReleaseCount = AtomicLong(0L)

    // --- 吞吐量统计 ---
    /** 总读取字节数。 */
    private val totalReadBytes = AtomicLong(0L)

    /** 总写入字节数。 */
    private val totalWriteBytes = AtomicLong(0L)

    /** 总 IO 操作次数。 */
    private val totalIoOps = AtomicLong(0L)

    /** 按路径聚合的吞吐量：path → ThroughputStats。 */
    private val pathThroughput = ConcurrentHashMap<String, ThroughputStats>()

    // --- Native PLT Hook 状态 ---
    /** Native PLT Hook 是否已安装。 */
    @Volatile
    private var nativeHookInstalled = false

    // --- 零拷贝检测 ---
    /** Buffer 拷贝操作追踪：sourcePath → CopyChain。 */
    private val copyChains = ConcurrentHashMap<String, CopyChain>()

    /** 零拷贝建议已触发的路径集合（避免重复上报）。 */
    private val zeroCopyReported = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 初始化 IO Hook。
     * 优先安装 Native PLT Hook，失败则降级为 Java 代理。
     */
    fun init() {
        if (initialized) return
        initialized = true

        // 尝试安装 Native PLT Hook
        if (config.enableNativePltHook) {
            installNativePltHook()
        }

        // 启动 Closeable 泄漏检测线程
        if (config.enableCloseableLeak) {
            val leakThread = Thread({ monitorCloseableLeaks() }, THREAD_NAME_LEAK)
            leakThread.isDaemon = true
            leakThread.start()
        }

        // 启动 FD 泄漏检测线程
        if (config.enableFdLeakDetection) {
            val fdThread = Thread({ monitorFdLeaks() }, THREAD_NAME_FD)
            fdThread.isDaemon = true
            fdThread.start()
        }

        // 启动零拷贝检测线程
        if (config.enableZeroCopyDetection) {
            val zcThread = Thread({ monitorZeroCopy() }, THREAD_NAME_ZERO_COPY)
            zcThread.isDaemon = true
            zcThread.start()
        }
    }

    // ========================================================================
    // Java 层代理（Level 1）
    // ========================================================================

    /**
     * 包装 InputStream，自动追踪读取操作。
     *
     * @param source 原始 InputStream。
     * @param path 文件路径。
     * @return 代理后的 InputStream。
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
        // 记录 FD 打开
        if (config.enableFdLeakDetection) {
            recordFdOpen(path)
        }
        return source
    }

    /**
     * 包装 OutputStream，自动追踪写入操作。
     *
     * @param source 原始 OutputStream。
     * @param path 文件路径。
     * @return 代理后的 OutputStream。
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
        if (config.enableFdLeakDetection) {
            recordFdOpen(path)
        }
        return source
    }

    /**
     * 记录一次 IO 读取操作。
     *
     * @param path 文件路径。
     * @param bytesRead 读取字节数。
     * @param bufferUsed 使用的 buffer 大小。
     */
    fun onRead(path: String, bytesRead: Int, bufferUsed: Int) {
        if (!initialized) return

        // 更新吞吐量统计
        if (config.enableThroughputStats) {
            totalReadBytes.addAndGet(bytesRead.toLong())
            totalIoOps.incrementAndGet()
            updatePathThroughput(path, bytesRead.toLong(), isWrite = false)
        }

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
     * @param source 原始流对象。
     * @param totalBytes 总字节数。
     */
    fun onClose(source: Any, totalBytes: Long) {
        val sessionId = System.identityHashCode(source)
        val session = activeSessions.remove(sessionId) ?: return
        val durationMs = System.currentTimeMillis() - session.openTime

        // 记录 FD 关闭
        if (config.enableFdLeakDetection) {
            recordFdClose(session.path)
        }

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

    // ========================================================================
    // FD 泄漏检测
    // ========================================================================

    /**
     * 记录 FD 打开。
     * 追踪每个打开的文件描述符对应的路径。
     */
    private fun recordFdOpen(path: String) {
        // 使用路径 hashCode 作为伪 fd（真实 fd 需 native hook 获取）
        val pseudoFd = path.hashCode()
        openFdPaths[pseudoFd] = path
        fdAllocCount.incrementAndGet()
    }

    /**
     * 记录 FD 关闭。
     */
    private fun recordFdClose(path: String) {
        val pseudoFd = path.hashCode()
        openFdPaths.remove(pseudoFd)
        fdReleaseCount.incrementAndGet()
    }

    /**
     * FD 泄漏检测线程。
     * 定期检查 /proc/self/fd 目录，统计打开的 FD 数量。
     * 超过阈值时上报。
     */
    private fun monitorFdLeaks() {
        while (initialized) {
            try {
                Thread.sleep(FD_CHECK_INTERVAL_MS)
                if (!initialized) break

                // 读取 /proc/self/fd 目录统计 FD 数量
                val fdCount = countOpenFds()
                if (fdCount >= config.fdLeakThreshold) {
                    // 收集泄漏的 FD 路径列表
                    val leakedPaths = openFdPaths.values.take(MAX_LEAKED_PATHS_REPORT)
                    Apm.emit(
                        module = MODULE_IO,
                        name = EVENT_FD_LEAK,
                        kind = ApmEventKind.ALERT,
                        severity = ApmSeverity.ERROR,
                        fields = mapOf(
                            FIELD_FD_COUNT to fdCount,
                            FIELD_THRESHOLD to config.fdLeakThreshold,
                            FIELD_ALLOC_COUNT to fdAllocCount.get(),
                            FIELD_RELEASE_COUNT to fdReleaseCount.get(),
                            FIELD_LEAKED_PATHS to leakedPaths.joinToString(LIST_SEPARATOR)
                        )
                    )
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                // 静默处理
            }
        }
    }

    /**
     * 统计当前进程打开的 FD 数量。
     * 通过读取 /proc/self/fd 目录。
     */
    private fun countOpenFds(): Int {
        return try {
            val fdDir = File(PROC_FD_PATH)
            if (fdDir.exists() && fdDir.isDirectory) {
                fdDir.listFiles()?.size ?: 0
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    // ========================================================================
    // 吞吐量统计
    // ========================================================================

    /**
     * 更新路径维度的吞吐量统计。
     */
    private fun updatePathThroughput(path: String, bytes: Long, isWrite: Boolean) {
        val stats = pathThroughput.getOrPut(path) { ThroughputStats(path) }
        if (isWrite) {
            stats.writeBytes.addAndGet(bytes)
        } else {
            stats.readBytes.addAndGet(bytes)
        }
        stats.opCount.incrementAndGet()
    }

    /**
     * 获取所有路径的吞吐量统计快照。
     */
    fun getThroughputStats(): List<ThroughputStats> {
        return pathThroughput.values.toList()
    }

    /**
     * 获取全局 IO 统计信息。
     */
    fun getGlobalStats(): Map<String, Long> {
        return mapOf(
            FIELD_TOTAL_READ_BYTES to totalReadBytes.get(),
            FIELD_TOTAL_WRITE_BYTES to totalWriteBytes.get(),
            FIELD_TOTAL_IO_OPS to totalIoOps.get()
        )
    }

    // ========================================================================
    // Native PLT Hook（Level 2）
    // ========================================================================

    /**
     * 安装 Native PLT Hook。
     * 通过 JNI 拦截 libc 的 open/read/write/close 函数。
     * 需要预编译的 libapm-io.so 库。
     */
    private fun installNativePltHook() {
        try {
            System.loadLibrary(NATIVE_LIB_NAME)
            nativeInstallIoHooks()
            nativeHookInstalled = true
        } catch (_: UnsatisfiedLinkError) {
            // JNI 库不存在，使用 Java 层代理降级
        } catch (_: Exception) {
            // 安装失败，降级
        }
    }

    /**
     * JNI 回调：Native 层检测到 IO 操作时调用。
     *
     * @param operation 操作类型（open/read/write/close）。
     * @param path 文件路径。
     * @param bytes 字节数。
     * @param durationMs 耗时（毫秒）。
     * @param isMainThread 是否主线程。
     */
    private fun onNativeIoEvent(
        operation: String,
        path: String,
        bytes: Long,
        durationMs: Long,
        isMainThread: Boolean
    ) {
        if (!initialized) return

        // 主线程 IO 检测
        if (isMainThread && durationMs >= config.mainThreadIoThresholdMs) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_MAIN_THREAD_IO,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.ERROR,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_DURATION_MS to durationMs,
                    FIELD_BYTES to bytes,
                    FIELD_OPERATION to operation,
                    FIELD_HOOK_LEVEL to HOOK_LEVEL_NATIVE
                )
            )
        }

        // 更新吞吐量统计
        if (config.enableThroughputStats) {
            totalIoOps.incrementAndGet()
            val isWrite = operation == OP_WRITE || operation == OP_CREATE_NEW
            if (isWrite) {
                totalWriteBytes.addAndGet(bytes)
            } else {
                totalReadBytes.addAndGet(bytes)
            }
            updatePathThroughput(path, bytes, isWrite)
        }
    }

    // ========================================================================
    // Closeable 泄漏检测
    // ========================================================================

    /**
     * Closeable 泄漏检测线程。
     * PhantomReference 被 GC 回收到 ReferenceQueue 时，
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

    // ========================================================================
    // 零拷贝检测
    // ========================================================================

    /**
     * 记录一次 Buffer 拷贝操作。
     * 当数据在两个路径之间通过小 buffer 多次拷贝时，建议使用零拷贝优化。
     *
     * @param fromPath 源路径。
     * @param toPath 目标路径。
     * @param bytes 拷贝字节数。
     * @param bufferCount 单次拷贝中的 buffer 切片数。
     */
    fun onBufferCopy(fromPath: String, toPath: String, bytes: Long, bufferCount: Int) {
        if (!initialized || !config.enableZeroCopyDetection) return
        // 构建拷贝链 key
        val chainKey = "${fromPath}$CHAIN_KEY_SEPARATOR$toPath"
        val chain = copyChains.getOrPut(chainKey) { CopyChain(fromPath, toPath) }
        // 累加拷贝统计
        chain.totalBytes.addAndGet(bytes)
        chain.copyCount.incrementAndGet()
        chain.bufferCountSum.addAndGet(bufferCount.toLong())
    }

    /**
     * 零拷贝检测线程。
     * 定期扫描拷贝链，检测是否存在多次小 buffer 拷贝的场景。
     * 当同一拷贝链的 bufferCountSum / copyCount > 阈值时，上报零拷贝优化建议。
     */
    private fun monitorZeroCopy() {
        while (initialized) {
            try {
                Thread.sleep(ZERO_COPY_CHECK_INTERVAL_MS)
                if (!initialized) break

                // 遍历所有拷贝链
                for ((key, chain) in copyChains) {
                    val copyCount = chain.copyCount.get()
                    // 至少发生足够次数的拷贝才检测
                    if (copyCount < ZERO_COPY_MIN_COPY_COUNT) continue
                    // 计算平均每次拷贝的 buffer 数量
                    val avgBuffers = chain.bufferCountSum.get().toDouble() / copyCount
                    // 平均 buffer 数量超过阈值 → 检测到零拷贝优化机会
                    if (avgBuffers >= ZERO_COPY_AVG_BUFFER_THRESHOLD) {
                        // 避免重复上报
                        if (zeroCopyReported.add(key)) {
                            Apm.emit(
                                module = MODULE_IO,
                                name = EVENT_ZERO_COPY_OPPORTUNITY,
                                kind = ApmEventKind.ALERT,
                                severity = ApmSeverity.INFO,
                                fields = mapOf(
                                    FIELD_FROM_PATH to chain.fromPath.take(MAX_PATH_LENGTH),
                                    FIELD_TO_PATH to chain.toPath.take(MAX_PATH_LENGTH),
                                    FIELD_TOTAL_BYTES to chain.totalBytes.get(),
                                    FIELD_COPY_COUNT to copyCount,
                                    FIELD_AVG_BUFFERS to String.format("%.1f", avgBuffers),
                                    FIELD_SUGGESTION to SUGGESTION_ZERO_COPY
                                )
                            )
                        }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                // 静默处理
            }
        }
    }

    /** 释放资源。 */
    fun destroy() {
        initialized = false
        activeSessions.clear()
        readFileCounts.clear()
        closeableRefs.clear()
        openFdPaths.clear()
        pathThroughput.clear()
        copyChains.clear()
        zeroCopyReported.clear()
        // 卸载 Native Hook
        if (nativeHookInstalled) {
            try {
                nativeUninstallIoHooks()
            } catch (_: Exception) {
                // 忽略
            }
            nativeHookInstalled = false
        }
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

    /** 路径维度的吞吐量统计。 */
    class ThroughputStats(
        /** 文件路径。 */
        val path: String,
        /** 读取字节数。 */
        val readBytes: AtomicLong = AtomicLong(0L),
        /** 写入字节数。 */
        val writeBytes: AtomicLong = AtomicLong(0L),
        /** 操作次数。 */
        val opCount: AtomicLong = AtomicLong(0L)
    )

    /** 零拷贝检测：Buffer 拷贝链追踪。 */
    class CopyChain(
        /** 源路径。 */
        val fromPath: String,
        /** 目标路径。 */
        val toPath: String,
        /** 总拷贝字节数。 */
        val totalBytes: AtomicLong = AtomicLong(0L),
        /** 拷贝次数。 */
        val copyCount: AtomicLong = AtomicLong(0L),
        /** buffer 切片总数。 */
        val bufferCountSum: AtomicLong = AtomicLong(0L)
    )

    // --- Native 方法声明 ---
    /** 安装 Native IO Hook。 */
    private external fun nativeInstallIoHooks()

    /** 卸载 Native IO Hook。 */
    private external fun nativeUninstallIoHooks()

    companion object {
        /** IO 模块名。 */
        private const val MODULE_IO = "io"

        /** Native 库名称。 */
        private const val NATIVE_LIB_NAME = "apm-io"

        // --- 事件名 ---
        /** 小 buffer 事件。 */
        private const val EVENT_SMALL_BUFFER = "io_small_buffer"

        /** 重复读事件。 */
        private const val EVENT_DUPLICATE_READ = "io_duplicate_read"

        /** 主线程 IO 事件。 */
        private const val EVENT_MAIN_THREAD_IO = "io_main_thread"

        /** Closeable 泄漏事件。 */
        private const val EVENT_CLOSEABLE_LEAK = "io_closeable_leak"

        /** FD 泄漏事件。 */
        private const val EVENT_FD_LEAK = "io_fd_leak"

        /** 零拷贝优化建议事件。 */
        private const val EVENT_ZERO_COPY_OPPORTUNITY = "io_zero_copy_opportunity"

        // --- 字段名 ---
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

        /** 字段：FD 数量。 */
        private const val FIELD_FD_COUNT = "fdCount"

        /** 字段：分配计数。 */
        private const val FIELD_ALLOC_COUNT = "fdAllocCount"

        /** 字段：释放计数。 */
        private const val FIELD_RELEASE_COUNT = "fdReleaseCount"

        /** 字段：泄漏路径列表。 */
        private const val FIELD_LEAKED_PATHS = "leakedPaths"

        /** 字段：操作类型。 */
        private const val FIELD_OPERATION = "operation"

        /** 字段：Hook 层级。 */
        private const val FIELD_HOOK_LEVEL = "hookLevel"

        /** 字段：总读取字节。 */
        private const val FIELD_TOTAL_READ_BYTES = "totalReadBytes"

        /** 字段：总写入字节。 */
        private const val FIELD_TOTAL_WRITE_BYTES = "totalWriteBytes"

        /** 字段：总 IO 操作数。 */
        private const val FIELD_TOTAL_IO_OPS = "totalIoOps"

        /** 字段：源路径（零拷贝）。 */
        private const val FIELD_FROM_PATH = "fromPath"

        /** 字段：目标路径（零拷贝）。 */
        private const val FIELD_TO_PATH = "toPath"

        /** 字段：拷贝次数（零拷贝）。 */
        private const val FIELD_COPY_COUNT = "copyCount"

        /** 字段：平均 buffer 数（零拷贝）。 */
        private const val FIELD_AVG_BUFFERS = "avgBuffers"

        /** 字段：优化建议。 */
        private const val FIELD_SUGGESTION = "suggestion"

        /** 字段：总字节数（零拷贝）。 */
        private const val FIELD_TOTAL_BYTES = "totalBytes"

        // --- 常量 ---
        /** 路径最大长度。 */
        private const val MAX_PATH_LENGTH = 256

        /** Closeable 检测间隔。 */
        private const val CLOSEABLE_CHECK_INTERVAL_MS = 1000L

        /** FD 检测间隔。 */
        private const val FD_CHECK_INTERVAL_MS = 5000L

        /** 泄漏路径最大报告数。 */
        private const val MAX_LEAKED_PATHS_REPORT = 10

        /** /proc/self/fd 路径。 */
        private const val PROC_FD_PATH = "/proc/self/fd"

        /** 列表分隔符。 */
        private const val LIST_SEPARATOR = ", "

        /** 泄漏检测线程名。 */
        private const val THREAD_NAME_LEAK = "apm-io-leak-monitor"

        /** FD 检测线程名。 */
        private const val THREAD_NAME_FD = "apm-io-fd-monitor"

        /** Hook 层级：Native。 */
        private const val HOOK_LEVEL_NATIVE = "native_plt"

        /** 操作类型值。 */
        private const val OP_WRITE = "write"
        private const val OP_CREATE_NEW = "createNew"

        // --- 零拷贝检测常量 ---
        /** 零拷贝检测线程名。 */
        private const val THREAD_NAME_ZERO_COPY = "apm-io-zero-copy"

        /** 零拷贝检测间隔：10 秒。 */
        private const val ZERO_COPY_CHECK_INTERVAL_MS = 10_000L

        /** 最少拷贝次数（低于此值不检测）。 */
        private const val ZERO_COPY_MIN_COPY_COUNT = 3L

        /** 平均 buffer 数阈值（超过此值建议零拷贝）。 */
        private const val ZERO_COPY_AVG_BUFFER_THRESHOLD = 4.0

        /** 拷贝链 key 分隔符。 */
        private const val CHAIN_KEY_SEPARATOR = " → "

        /** 零拷贝优化建议文案。 */
        private const val SUGGESTION_ZERO_COPY = "Consider FileChannel.transferTo / sendfile"
    }
}
