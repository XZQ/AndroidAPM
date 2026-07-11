package com.apm.io

import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmExecutors
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * Level 2 不可用时，已显式安装的 Level 1 wrapper 继续工作；模块不会接管任意 Java 流。
 */
class NativeIoHook(private val config: IoConfig) {

    /** 活跃的 IO 会话：System.identityHashCode → IoSession。 */
    private val activeSessions = ConcurrentHashMap<Int, IoSession>()

    /** 文件读取计数器：path → 读取次数。 */
    private val readFileCounts = ConcurrentHashMap<String, Int>()

    /** Bounded paths that already emitted a small-buffer finding. */
    private val smallBufferReportedPaths = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Closeable leak tracking metadata keyed by phantom reference. */
    private val closeableRefs = ConcurrentHashMap<PhantomReference<Any>, CloseableMetadata>()

    /** Session-to-reference index used to cancel tracking on explicit close. */
    private val sessionRefs = ConcurrentHashMap<Int, PhantomReference<Any>>()

    /** Weak proxy-to-session lookup used by explicit close callbacks. */
    private val proxySessionIds = Collections.synchronizedMap(WeakHashMap<Any, Int>())

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

    /** Unique Java proxy session identifier generator. */
    private val nextSessionId = AtomicInteger(0)

    // --- 吞吐量统计 ---
    /** 总读取字节数。 */
    private val totalReadBytes = AtomicLong(0L)

    /** 总写入字节数。 */
    private val totalWriteBytes = AtomicLong(0L)

    /** 总 IO 操作次数。 */
    private val totalIoOps = AtomicLong(0L)

    /** 按路径聚合的吞吐量：path → ThroughputStats。 */
    private val pathThroughput = ConcurrentHashMap<String, ThroughputStats>()

    /** Per-thread nesting marker used to suppress Native callbacks for explicitly wrapped calls. */
    private val javaProxyIoDepth = ThreadLocal<Int>()

    // --- Native PLT Hook 状态 ---
    /** Native PLT Hook 是否已安装。 */
    @Volatile
    private var nativeHookInstalled = false

    /** Native Hook 安装器，封装 JNI 加载和降级判断。 */
    private val nativeHookInstaller = NativeIoHookInstaller(
        loadLibrary = { System.loadLibrary(NATIVE_LIB_NAME) },
        installHooks = { nativeInstallIoHooks() },
        uninstallHooks = { nativeUninstallIoHooks() }
    )

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
        if (initialized) {
            return
        }
        initialized = true

        // 注册为 JNI 静态回调的活跃实例，Native 事件经伴生对象桥接到本实例
        activeHook = this

        // 尝试安装 Native PLT Hook
        if (config.enableNativePltHook) {
            installNativePltHook()
        }

        // 三个后台监视器统一走 ApmExecutors，保证 daemon + "apm-" 前缀命名 + 最低优先级，
        // 既便于线程 dump 定位，也不会阻止宿主进程退出。
        if (config.enableCloseableLeak) {
            ApmExecutors.startThread(THREAD_NAME_LEAK) { monitorCloseableLeaks() }
        }

        if (config.enableFdLeakDetection) {
            ApmExecutors.startThread(THREAD_NAME_FD) { monitorFdLeaks() }
        }

        if (config.enableZeroCopyDetection) {
            ApmExecutors.startThread(THREAD_NAME_ZERO_COPY) { monitorZeroCopy() }
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
        val wrapper = object : InputStream() {
            /** Total bytes read through this proxy. */
            private var totalBytes = 0L

            /** Explicit close guard. */
            private var closed = false

            /** Reads one byte and records the operation. */
            override fun read(): Int {
                val startMs = monotonicTimeMs()
                val value = withJavaProxyIo { source.read() }
                val durationMs = monotonicTimeMs() - startMs
                monitorSafely { reportProxyLatency(path, OP_READ, durationMs, if (value >= 0) 1L else 0L) }
                if (value >= 0) {
                    totalBytes++
                    monitorSafely { onRead(path, 1, 1) }
                }
                return value
            }

            /** Reads a byte range and records the actual byte count. */
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val startMs = monotonicTimeMs()
                val count = withJavaProxyIo { source.read(buffer, offset, length) }
                val durationMs = monotonicTimeMs() - startMs
                monitorSafely { reportProxyLatency(path, OP_READ, durationMs, count.coerceAtLeast(0).toLong()) }
                if (count > 0) {
                    totalBytes += count
                    monitorSafely { onRead(path, count, length) }
                }
                return count
            }

            /** Skips bytes through the source stream. */
            override fun skip(byteCount: Long): Long = source.skip(byteCount)

            /** Returns the source stream's available byte count. */
            override fun available(): Int = source.available()

            /** Reports whether mark/reset is supported by the source. */
            override fun markSupported(): Boolean = source.markSupported()

            /** Marks the source stream. */
            @Synchronized
            override fun mark(readLimit: Int) = source.mark(readLimit)

            /** Resets the source stream to its mark. */
            @Synchronized
            override fun reset() = source.reset()

            /** Closes the source and completes the tracking session once. */
            override fun close() {
                if (closed) {
                    return
                }
                closed = true
                val startMs = monotonicTimeMs()
                try {
                    withJavaProxyIo { source.close() }
                } finally {
                    val durationMs = monotonicTimeMs() - startMs
                    monitorSafely { reportProxyLatency(path, OP_CLOSE, durationMs, totalBytes) }
                    monitorSafely { onClose(this, totalBytes) }
                }
            }
        }
        monitorSafely { registerSession(wrapper, path) }
        return wrapper
    }

    /**
     * 包装 OutputStream，自动追踪写入操作。
     *
     * @param source 原始 OutputStream。
     * @param path 文件路径。
     * @return 代理后的 OutputStream。
     */
    fun wrapOutputStream(source: OutputStream, path: String): OutputStream {
        val wrapper = object : OutputStream() {
            /** Total bytes written through this proxy. */
            private var totalBytes = 0L

            /** Explicit close guard. */
            private var closed = false

            /** Writes one byte and records the operation. */
            override fun write(value: Int) {
                val startMs = monotonicTimeMs()
                withJavaProxyIo { source.write(value) }
                val durationMs = monotonicTimeMs() - startMs
                monitorSafely { reportProxyLatency(path, OP_WRITE, durationMs, 1L) }
                totalBytes++
                monitorSafely { onWrite(path, 1, 1) }
            }

            /** Writes a byte range without per-byte double counting. */
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                val startMs = monotonicTimeMs()
                withJavaProxyIo { source.write(buffer, offset, length) }
                val durationMs = monotonicTimeMs() - startMs
                monitorSafely { reportProxyLatency(path, OP_WRITE, durationMs, length.toLong()) }
                totalBytes += length
                monitorSafely { onWrite(path, length, length) }
            }

            /** Flushes the source stream. */
            override fun flush() {
                val startMs = monotonicTimeMs()
                withJavaProxyIo { source.flush() }
                val durationMs = monotonicTimeMs() - startMs
                monitorSafely { reportProxyLatency(path, OP_FLUSH, durationMs, 0L) }
            }

            /** Closes the source and completes the tracking session once. */
            override fun close() {
                if (closed) {
                    return
                }
                closed = true
                val startMs = monotonicTimeMs()
                try {
                    withJavaProxyIo { source.close() }
                } finally {
                    val durationMs = monotonicTimeMs() - startMs
                    monitorSafely { reportProxyLatency(path, OP_CLOSE, durationMs, totalBytes) }
                    monitorSafely { onClose(this, totalBytes) }
                }
            }
        }
        monitorSafely { registerSession(wrapper, path) }
        return wrapper
    }

    /**
     * Registers one Java proxy session and optional leak tracking.
     *
     * @param proxy proxy object returned to the caller
     * @param path logical file path
     * @return unique session identifier
     */
    private fun registerSession(proxy: Any, path: String): Int {
        val sessionId = nextSessionId.incrementAndGet()
        proxySessionIds[proxy] = sessionId
        activeSessions[sessionId] = IoSession(
            path = path,
            openTime = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            isMainThread = Looper.myLooper() == Looper.getMainLooper()
        )
        if (config.enableCloseableLeak) {
            val reference = PhantomReference(proxy, closeableQueue)
            closeableRefs[reference] = CloseableMetadata(sessionId, path)
            sessionRefs[sessionId] = reference
        }
        if (config.enableFdLeakDetection) {
            recordFdOpen(sessionId, path)
        }
        return sessionId
    }

    /**
     * 记录一次 IO 读取操作。
     *
     * @param path 文件路径。
     * @param bytesRead 读取字节数。
     * @param bufferUsed 使用的 buffer 大小。
     */
    fun onRead(path: String, bytesRead: Int, bufferUsed: Int) {
        if (!initialized) {
            return
        }
        if (bytesRead <= 0) {
            return
        }

        // 更新吞吐量统计
        if (ThroughputSourcePolicy.shouldCountJava(config.enableThroughputStats)) {
            totalReadBytes.addAndGet(bytesRead.toLong())
            val operationCount = totalIoOps.incrementAndGet()
            updatePathThroughput(path, bytesRead.toLong(), isWrite = false)
            maybeReportThroughput(operationCount)
        }

        // 小 buffer 检测
        if (shouldReportSmallBuffer(path, bufferUsed)) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_SMALL_BUFFER,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.INFO, priority = ApmPriority.NORMAL,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_BUFFER_SIZE to bufferUsed,
                    FIELD_THRESHOLD to config.smallBufferThreshold
                )
            )
        }

        // 重复读检测
        val readCount = incrementBoundedReadCount(path)
        if (readCount != null && DuplicateReadGate.shouldReport(readCount, config.duplicateReadThreshold)) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_DUPLICATE_READ,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN, priority = ApmPriority.NORMAL,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_READ_COUNT to readCount,
                    FIELD_THRESHOLD to config.duplicateReadThreshold
                )
            )
        }
    }

    /**
     * Records one proxied write operation.
     *
     * @param path file path
     * @param bytesWritten written byte count
     * @param bufferUsed caller buffer size
     */
    fun onWrite(path: String, bytesWritten: Int, bufferUsed: Int) {
        if (!initialized || bytesWritten <= 0) {
            return
        }
        if (ThroughputSourcePolicy.shouldCountJava(config.enableThroughputStats)) {
            totalWriteBytes.addAndGet(bytesWritten.toLong())
            val operationCount = totalIoOps.incrementAndGet()
            updatePathThroughput(path, bytesWritten.toLong(), isWrite = true)
            maybeReportThroughput(operationCount)
        }
        if (shouldReportSmallBuffer(path, bufferUsed)) {
            Apm.emit(
                module = MODULE_IO,
                name = EVENT_SMALL_BUFFER,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.INFO,
                priority = ApmPriority.NORMAL,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_BUFFER_SIZE to bufferUsed,
                    FIELD_THRESHOLD to config.smallBufferThreshold,
                    FIELD_OPERATION to OP_WRITE
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
        val sessionId = proxySessionIds.remove(source) ?: return
        activeSessions.remove(sessionId) ?: return
        sessionRefs.remove(sessionId)?.let { reference ->
            closeableRefs.remove(reference)
            reference.clear()
        }
        // 记录 FD 关闭
        if (config.enableFdLeakDetection) {
            recordFdClose(sessionId)
        }
        // Stream lifetime is not operation latency; wrapper call boundaries report actual duration.
    }

    // ========================================================================
    // FD 泄漏检测
    // ========================================================================

    /**
     * 记录 FD 打开。
     * 追踪每个打开的文件描述符对应的路径。
     */
    private fun recordFdOpen(sessionId: Int, path: String) {
        openFdPaths[sessionId] = path
        fdAllocCount.incrementAndGet()
    }

    /**
     * 记录 FD 关闭。
     */
    private fun recordFdClose(sessionId: Int) {
        openFdPaths.remove(sessionId)
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
                if (!initialized) {
                    break
                }

                // 读取 /proc/self/fd 目录统计 FD 数量
                val fdCount = countOpenFds()
                if (fdCount >= config.fdLeakThreshold) {
                    // 收集泄漏的 FD 路径列表
                    val leakedPaths = openFdPaths.values.take(MAX_LEAKED_PATHS_REPORT)
                    Apm.emit(
                        module = MODULE_IO,
                        name = EVENT_FD_LEAK,
                        kind = ApmEventKind.ALERT,
                        severity = ApmSeverity.ERROR, priority = ApmPriority.NORMAL,
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
            } catch (e: Exception) {
                // FD 泄漏检测单轮失败不中断轮询，但记入自监控
                Apm.recordInternalError(ERROR_TAG_FD_LEAK_LOOP, e)
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
        val stats = pathThroughput[path] ?: synchronized(pathThroughput) {
            pathThroughput[path] ?: run {
                // Reserve one of the bounded entries for high-cardinality overflow.
                val metricPath = if (pathThroughput.size < MAX_TRACKED_PATHS - 1) {
                    path
                } else {
                    PATH_OVERFLOW_BUCKET
                }
                pathThroughput.getOrPut(metricPath) { ThroughputStats(metricPath) }
            }
        }
        if (isWrite) {
            stats.writeBytes.addAndGet(bytes)
        } else {
            stats.readBytes.addAndGet(bytes)
        }
        stats.opCount.incrementAndGet()
    }

    /** Increments duplicate-read state without allowing unique paths to grow memory without bound. */
    private fun incrementBoundedReadCount(path: String): Int? {
        readFileCounts.computeIfPresent(path) { _, count -> count + 1 }?.let { return it }
        synchronized(readFileCounts) {
            readFileCounts.computeIfPresent(path) { _, count -> count + 1 }?.let { return it }
            if (readFileCounts.size >= MAX_TRACKED_PATHS) {
                return null
            }
            readFileCounts[path] = 1
            return 1
        }
    }

    /** Reports actual explicit-wrapper operation latency without using stream lifetime as duration. */
    private fun reportProxyLatency(path: String, operation: String, durationMs: Long, bytes: Long) {
        if (durationMs < minOf(config.mainThreadIoThresholdMs, config.singleIoThresholdMs)) {
            return
        }
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        val slowOnMain = isMainThread && durationMs >= config.mainThreadIoThresholdMs
        val slowOperation = durationMs >= config.singleIoThresholdMs
        if (!slowOnMain && !slowOperation) {
            return
        }
        Apm.emit(
            module = MODULE_IO,
            name = if (slowOnMain) EVENT_MAIN_THREAD_IO else EVENT_IO_ISSUE,
            kind = ApmEventKind.ALERT,
            severity = if (slowOnMain) ApmSeverity.ERROR else ApmSeverity.WARN,
            priority = ApmPriority.NORMAL,
            fields = mapOf(
                FIELD_PATH to path.take(MAX_PATH_LENGTH),
                FIELD_DURATION_MS to durationMs,
                FIELD_BYTES to bytes,
                FIELD_IS_MAIN_THREAD to isMainThread,
                FIELD_OPERATION to operation,
                FIELD_HOOK_LEVEL to HOOK_LEVEL_JAVA
            )
        )
    }

    /** Atomically gates small-buffer findings to one event per bounded path lifecycle. */
    private fun shouldReportSmallBuffer(path: String, bufferSize: Int): Boolean {
        if (!SmallBufferGate.shouldReport(
                bufferSize = bufferSize,
                threshold = config.smallBufferThreshold,
                alreadyReported = smallBufferReportedPaths.contains(path)
            )
        ) {
            return false
        }
        synchronized(smallBufferReportedPaths) {
            if (!SmallBufferGate.shouldReport(
                    bufferSize = bufferSize,
                    threshold = config.smallBufferThreshold,
                    alreadyReported = smallBufferReportedPaths.contains(path)
                ) || smallBufferReportedPaths.size >= MAX_TRACKED_PATHS
            ) {
                return false
            }
            return smallBufferReportedPaths.add(path)
        }
    }

    /** Emits one cumulative throughput snapshot at the configured operation window. */
    private fun maybeReportThroughput(operationCount: Long) {
        val window = config.throughputWindow.coerceAtLeast(1).toLong()
        if (!ThroughputWindowGate.shouldReport(operationCount, config.throughputWindow)) {
            return
        }
        Apm.emit(
            module = MODULE_IO,
            name = EVENT_THROUGHPUT,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            priority = ApmPriority.LOW,
            fields = getGlobalStats() + mapOf(FIELD_THROUGHPUT_WINDOW to window)
        )
    }

    /** Isolates proxy bookkeeping so completed host IO never appears to fail because monitoring degraded. */
    private inline fun monitorSafely(block: () -> Unit) {
        try {
            block()
        } catch (error: RuntimeException) {
            Apm.recordInternalError(ERROR_TAG_JAVA_CALLBACK, error)
        }
    }

    /** Returns monotonic milliseconds for operation-duration measurement. */
    private fun monotonicTimeMs(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    /** Executes one source call while marking synchronous Native callbacks as represented by the wrapper. */
    private inline fun <T> withJavaProxyIo(block: () -> T): T {
        val previousDepth = javaProxyIoDepth.get() ?: 0
        javaProxyIoDepth.set(previousDepth + 1)
        try {
            return block()
        } finally {
            if (previousDepth == 0) {
                javaProxyIoDepth.remove()
            } else {
                javaProxyIoDepth.set(previousDepth)
            }
        }
    }

    /** Returns true on the thread currently executing an explicit wrapper's source call. */
    private fun isInsideJavaProxyIo(): Boolean = (javaProxyIoDepth.get() ?: 0) > 0

    /**
     * 获取所有路径的吞吐量统计快照。
     */
    fun getThroughputStats(): List<ThroughputStats> {
        return pathThroughput.values.map { stats ->
            ThroughputStats(
                path = stats.path,
                readBytes = AtomicLong(stats.readBytes.get()),
                writeBytes = AtomicLong(stats.writeBytes.get()),
                opCount = AtomicLong(stats.opCount.get())
            )
        }
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
        // Native 不可用时保持 false，调用方继续使用 Java 代理路径。
        nativeHookInstalled = nativeHookInstaller.install()
    }

    /**
     * 处理 Native 层上报的 IO 事件。
     * 由伴生对象的静态 JNI 桥接方法 [onNativeIoEvent] 转发到当前活跃实例。
     *
     * @param operation 操作类型（open/read/write/close）。
     * @param path 文件路径。
     * @param bytes 字节数。
     * @param durationMs 耗时（毫秒）。
     * @param isMainThread 是否主线程。
     */
    internal fun handleNativeIoEvent(operation: String, path: String, bytes: Long, durationMs: Long, isMainThread: Boolean) {
        val insideJavaProxy = isInsideJavaProxyIo()
        if (!initialized) {
            return
        }
        val isReadOrWrite = operation == OP_READ || operation == OP_WRITE

        val slowOnMain = isMainThread && durationMs >= config.mainThreadIoThresholdMs
        val slowOperation = durationMs >= config.singleIoThresholdMs
        // Only read/write callbacks carry actual syscall duration; wrapper-marked calls report on the Java boundary.
        if (isReadOrWrite && !insideJavaProxy && (slowOnMain || slowOperation)) {
            Apm.emit(
                module = MODULE_IO,
                name = if (slowOnMain) EVENT_MAIN_THREAD_IO else EVENT_IO_ISSUE,
                kind = ApmEventKind.ALERT,
                severity = if (slowOnMain) ApmSeverity.ERROR else ApmSeverity.WARN,
                priority = ApmPriority.NORMAL,
                fields = mapOf(
                    FIELD_PATH to path.take(MAX_PATH_LENGTH),
                    FIELD_DURATION_MS to durationMs,
                    FIELD_BYTES to bytes,
                    FIELD_IS_MAIN_THREAD to isMainThread,
                    FIELD_OPERATION to operation,
                    FIELD_HOOK_LEVEL to HOOK_LEVEL_NATIVE
                )
            )
        }

        // 更新吞吐量统计
        if (ThroughputSourcePolicy.shouldCountNative(config.enableThroughputStats, insideJavaProxy) &&
            isReadOrWrite && bytes > 0L
        ) {
            val operationCount = totalIoOps.incrementAndGet()
            val isWrite = operation == OP_WRITE
            if (isWrite) {
                totalWriteBytes.addAndGet(bytes)
            } else {
                totalReadBytes.addAndGet(bytes)
            }
            updatePathThroughput(path, bytes, isWrite)
            maybeReportThroughput(operationCount)
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
                    val metadata = closeableRefs.remove(ref)
                    if (metadata != null) {
                        sessionRefs.remove(metadata.sessionId)
                        activeSessions.remove(metadata.sessionId)
                        openFdPaths.remove(metadata.sessionId)
                        Apm.emit(
                            module = MODULE_IO,
                            name = EVENT_CLOSEABLE_LEAK,
                            kind = ApmEventKind.ALERT,
                            severity = ApmSeverity.WARN, priority = ApmPriority.NORMAL,
                            fields = mapOf(FIELD_PATH to metadata.path.take(MAX_PATH_LENGTH))
                        )
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                // Closeable 泄漏检测单轮失败不中断轮询，但记入自监控
                Apm.recordInternalError(ERROR_TAG_CLOSEABLE_LEAK_LOOP, e)
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
        if (!initialized || !config.enableZeroCopyDetection) {
            return
        }
        // 构建拷贝链 key
        val chainKey = "${fromPath}$CHAIN_KEY_SEPARATOR$toPath"
        val chain = copyChains[chainKey] ?: synchronized(copyChains) {
            copyChains[chainKey] ?: run {
                if (copyChains.size >= MAX_TRACKED_COPY_CHAINS) {
                    return
                }
                CopyChain(fromPath, toPath).also { copyChains[chainKey] = it }
            }
        }
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
                if (!initialized) {
                    break
                }

                // 遍历所有拷贝链
                for ((key, chain) in copyChains) {
                    val copyCount = chain.copyCount.get()
                    // 至少发生足够次数的拷贝才检测
                    if (copyCount < ZERO_COPY_MIN_COPY_COUNT) {
                        continue
                    }
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
                                severity = ApmSeverity.INFO, priority = ApmPriority.NORMAL,
                                fields = mapOf(
                                    FIELD_FROM_PATH to chain.fromPath.take(MAX_PATH_LENGTH),
                                    FIELD_TO_PATH to chain.toPath.take(MAX_PATH_LENGTH),
                                    FIELD_TOTAL_BYTES to chain.totalBytes.get(),
                                    FIELD_COPY_COUNT to copyCount,
                                    FIELD_AVG_BUFFERS to String.format(Locale.ROOT, "%.1f", avgBuffers),
                                    FIELD_SUGGESTION to SUGGESTION_ZERO_COPY
                                )
                            )
                        }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (error: Exception) {
                // A failed scan must not terminate the monitor, but remains visible in SDK diagnostics.
                Apm.recordInternalError(ERROR_TAG_ZERO_COPY_LOOP, error)
            }
        }
    }

    /** 释放资源。 */
    fun destroy() {
        initialized = false
        // 注销 JNI 静态回调的活跃实例，避免销毁后继续接收 Native 事件
        if (activeHook === this) {
            activeHook = null
        }
        activeSessions.clear()
        readFileCounts.clear()
        smallBufferReportedPaths.clear()
        closeableRefs.clear()
        sessionRefs.clear()
        proxySessionIds.clear()
        openFdPaths.clear()
        pathThroughput.clear()
        copyChains.clear()
        zeroCopyReported.clear()
        // 卸载 Native Hook
        if (nativeHookInstalled) {
            nativeHookInstaller.uninstall()
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

    /**
     * Metadata retained without strongly referencing the tracked proxy.
     *
     * @property sessionId unique proxy session id
     * @property path logical file path
     */
    private data class CloseableMetadata(val sessionId: Int, val path: String)

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
        /** 自监控 tag：FD 泄漏检测轮询失败。 */
        private const val ERROR_TAG_FD_LEAK_LOOP = "io_fd_leak_loop"

        /** 自监控 tag：Closeable 泄漏检测轮询失败。 */
        private const val ERROR_TAG_CLOSEABLE_LEAK_LOOP = "io_closeable_leak_loop"

        /** Self-monitoring tag for zero-copy scan failures. */
        private const val ERROR_TAG_ZERO_COPY_LOOP = "io_zero_copy_loop"

        /** Self-monitoring tag for an isolated Java proxy callback failure. */
        private const val ERROR_TAG_JAVA_CALLBACK = "io_java_proxy_callback"

        /**
         * 当前接收 Native IO 事件的活跃实例。
         * JNI 层以静态方法查找回调（GetStaticMethodID），因此静态桥接方法
         * 需要通过该引用把事件转发给持有配置与统计状态的实例。
         */
        @Volatile
        private var activeHook: NativeIoHook? = null

        /**
         * JNI 静态回调：Native 层检测到 IO 操作时调用。
         * 必须保持 @JvmStatic 且签名为 (String, String, long, long, boolean)，
         * 与 apm_io_jni.c 中 CALLBACK_METHOD_SIG 的静态方法查找一致。
         *
         * @param operation 操作类型（open/read/write/close）。
         * @param path 文件路径。
         * @param bytes 字节数。
         * @param durationMs 耗时（毫秒）。
         * @param isMainThread 是否主线程。
         */
        @JvmStatic
        private fun onNativeIoEvent(operation: String, path: String, bytes: Long, durationMs: Long, isMainThread: Boolean) {
            // 无活跃实例（未初始化或已销毁）时直接丢弃事件
            activeHook?.handleNativeIoEvent(operation, path, bytes, durationMs, isMainThread)
        }

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

        /** Slow background IO event shared with the module-level manual callback. */
        private const val EVENT_IO_ISSUE = "io_issue"

        /** Closeable 泄漏事件。 */
        private const val EVENT_CLOSEABLE_LEAK = "io_closeable_leak"

        /** FD 泄漏事件。 */
        private const val EVENT_FD_LEAK = "io_fd_leak"

        /** 零拷贝优化建议事件。 */
        private const val EVENT_ZERO_COPY_OPPORTUNITY = "io_zero_copy_opportunity"

        /** Cumulative IO throughput snapshot event. */
        private const val EVENT_THROUGHPUT = "io_throughput"

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

        /** Field: whether the measured operation ran on the main thread. */
        private const val FIELD_IS_MAIN_THREAD = "isMainThread"

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

        /** Field: configured operation count per throughput report. */
        private const val FIELD_THROUGHPUT_WINDOW = "throughputWindow"

        /** Maximum unique paths retained for duplicate-read and throughput diagnostics. */
        private const val MAX_TRACKED_PATHS = 256

        /** Overflow bucket used when unique throughput paths exceed the bound. */
        private const val PATH_OVERFLOW_BUCKET = "<other>"

        /** Maximum source/destination copy chains retained by zero-copy analysis. */
        private const val MAX_TRACKED_COPY_CHAINS = 256

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

        /** Nanoseconds contained in one millisecond. */
        private const val NANOS_PER_MILLISECOND = 1_000_000L

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
        /** Hook level for explicit Java stream wrappers. */
        private const val HOOK_LEVEL_JAVA = "java_wrapper"

        /** 操作类型值。 */
        private const val OP_WRITE = "write"
        /** Native read operation name. */
        private const val OP_READ = "read"
        /** Explicit stream flush operation name. */
        private const val OP_FLUSH = "flush"
        /** Explicit stream close operation name. */
        private const val OP_CLOSE = "close"

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
