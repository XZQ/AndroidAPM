package com.apm.core

import android.app.Application
import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import com.apm.storage.EventDbHelper
import com.apm.storage.EventStore
import com.apm.storage.FileEventStore
import com.apm.storage.SQLiteEventStore
import com.apm.core.aggregation.EventAggregator
import com.apm.core.privacy.DefaultSanitizationRules
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.throttle.RateLimiter
import com.apm.core.selfmonitor.AutoThrottle
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.core.diagnostics.ApmDiagnostics
import com.apm.core.diagnostics.DiagnosticLevel
import com.apm.uploader.ApmUploader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runs independent internal-error sinks without allowing one recoverable failure to block the other. */
internal inline fun recordInternalErrorSafely(
    selfMonitorSink: () -> Unit,
    diagnosticsSink: () -> Unit
) {
    try {
        selfMonitorSink()
    } catch (_: Exception) {
        // The diagnostics sink must still receive the original failure.
    }
    try {
        diagnosticsSink()
    } catch (_: Exception) {
        // Diagnostics failures cannot recurse through the no-throw error boundary.
    }
}

/** Captures an immutable host context while degrading every recoverable provider exception. */
internal inline fun captureBizContextSafely(
    provider: BizContextProvider,
    onError: (Exception) -> Unit
): Map<String, String> {
    return try {
        provider.currentContext().toMap()
    } catch (error: Exception) {
        onError(error)
        emptyMap()
    }
}

/** Runs one recoverable lifecycle phase and leaves fatal VM errors visible. */
internal inline fun runRecoverableBoundary(
    block: () -> Unit,
    onFailure: (Exception) -> Unit
): Boolean {
    return try {
        block()
        true
    } catch (error: Exception) {
        onFailure(error)
        false
    }
}

/**
 * APM 框架统一入口。单例对象。
 *
 * 职责：
 * - 初始化框架基础设施（存储、上传、限流）
 * - 管理模块注册和生命周期
 * - 提供统一的事件发射接口
 *
 * 使用方式：
 * ```kotlin
 * Apm.init(application, ApmConfig())
 * Apm.register(MemoryModule())
 * ```
 */
object Apm {

    /** 已注册的模块列表。CopyOnWriteArrayList 保证并发安全。 */
    private val modules = CopyOnWriteArrayList<ApmModule>()

    /** 框架运行状态。非 null 表示已初始化。 */
    @Volatile
    private var state: State? = null

    /** 初始化锁，防止多线程并发 init 导致竞态条件。 */
    private val initLock = Any()

    /**
     * 初始化 APM 框架。只能在主线程调用一次。
     *
     * 执行流程：
     * 1. 加锁检查是否已初始化（防重入）
     * 2. 检查进程策略，子进程可能跳过初始化
     * 3. 创建存储、上传、限流、分发器等基础设施
     * 4. 启动所有已注册的模块
     *
     * @param application 宿主 Application
     * @param config 全局配置
     */
    fun init(application: Application, config: ApmConfig) {
        synchronized(initLock) {
            if (state != null) {
                return
            }
            try {
                doInit(application, config)
            } catch (error: Exception) {
                // Preserve partial-init evidence locally, then close only the diagnostics writer thread.
                ApmDiagnostics.record(
                    DiagnosticLevel.ERROR,
                    CORE_MODULE,
                    ERROR_CODE_INIT,
                    MESSAGE_INIT_FAILED,
                    error
                )
                ApmDiagnostics.flush(DIAGNOSTICS_FLUSH_TIMEOUT_MS)
                ApmDiagnostics.shutdown()
                throw error
            }
        }
    }

    /**
     * 实际初始化逻辑。已由外部 synchronized 保证线程安全。
     */
    private fun doInit(application: Application, config: ApmConfig) {
        val processName = application.currentProcessNameCompat()
        // Diagnostics starts before event storage and upload so their initialization failures remain inspectable.
        val diagnostics = ApmDiagnostics.initialize(application, config.diagnostics, processName)
        val logger = AndroidApmLogger(config.debugLogging, diagnostics)
        diagnostics?.record(
            DiagnosticLevel.INFO,
            CORE_MODULE,
            EVENT_DIAGNOSTIC_SESSION_START,
            MESSAGE_INIT_STARTED,
            null
        )

        // 根据进程策略决定是否跳过非主进程
        if (config.processStrategy == ProcessStrategy.MAIN_PROCESS_ONLY &&
            !application.isMainProcessCompat()
        ) {
            logger.d("Skip init in non-main process: $processName")
            ApmDiagnostics.shutdown()
            return
        }

        var stagedStore: EventStore? = null
        var stagedUploader: ApmUploader? = null
        var stagedDispatcher: ApmDispatcher? = null
        var stagedCoordinator: ProcessEventCoordinator? = null
        var stagedMonitoringExecutor: ScheduledExecutorService? = null
        try {
        // 创建本地存储：根据配置选择文件存储或 SQLite 持久化存储
        val store: EventStore = when (config.storageType) {
            StorageType.SQLITE -> {
                val dbHelper = EventDbHelper(application)
                SQLiteEventStore(dbHelper)
            }
            StorageType.FILE -> {
                // FILE 存储不实现 PendingEventStore，持久 outbox（崩溃/重启重放、
                // 成功确认删除）会被静默关闭，上传退化为尽力而为——显式警告接入方
                logger.w(
                    "StorageType.FILE has no durable outbox: uploads are " +
                        "fire-and-forget and events are not replayed after restart. " +
                        "Use StorageType.SQLITE for durable delivery."
                )
                FileEventStore(application)
            }
        }
        stagedStore = store

        // 上传通道：优先使用显式自定义 uploader，其次按 endpoint 自动推导。
        val uploader: ApmUploader = UploaderFactory.create(
            config = config,
            durableStore = store is com.apm.storage.PendingEventStore,
            logger = logger.withComponent(UPLOADER_MODULE)
        )
        stagedUploader = uploader

        // 限流器：按 module/name 分桶，超出配额的事件被丢弃
        val rateLimiter = if (config.rateLimitEventsPerWindow > 0) {
            RateLimiter(config.rateLimitEventsPerWindow, config.rateLimitWindowMs)
        } else null

        // 聚合器：高频 METRIC 事件滑动窗口聚合 + ALERT 栈指纹去重
        val aggregator = if (config.enableAggregation) {
            EventAggregator(
                windowMs = config.aggregationWindowMs,
                enabled = true,
                logger = logger.withComponent(AGGREGATION_MODULE)
            )
        } else null

        // PII 脱敏器：上报前自动去除手机号/邮箱/身份证/敏感URL参数
        val piiSanitizer = if (config.enablePiiSanitization) {
            PiiSanitizer(
                rules = DefaultSanitizationRules.all() + config.customSanitizationRules,
                logger = logger.withComponent(PRIVACY_MODULE)
            )
        } else null

        // SDK self monitoring is wired before worker construction so queue and
        // upload metrics include restart replay from the first cycle.
        val selfMonitor = if (config.enableSelfMonitoring) SdkSelfMonitor() else null

        // 组装分发器和上下文
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = uploader,
            logger = logger.withComponent(DISPATCHER_MODULE),
            rateLimiter = rateLimiter,
            aggregator = aggregator,
            piiSanitizer = piiSanitizer,
            selfMonitor = selfMonitor,
            retryPolicy = com.apm.uploader.RetryPolicy(
                maxRetries = if (config.enableRetry) config.maxRetries else 0,
                baseDelayMs = config.retryBaseDelayMs
            ),
            uploadBatchSize = config.uploadBatchSize,
            uploadLeaseDurationMs = config.uploadLeaseDurationMs
        )
        stagedDispatcher = dispatcher
        val isUploaderProcess = application.isMainProcessCompat()
        val processCoordinator = if (config.enableMultiProcessCoordination) {
            val coordinator = ProcessEventCoordinator(application, isUploaderProcess)
            stagedCoordinator = coordinator
            coordinator.onRemoteEvent = dispatcher::dispatch
            coordinator.start()
            coordinator
        } else {
            null
        }
        stagedCoordinator = processCoordinator
        val context = ApmContext(
            application = application,
            config = config,
            processName = processName,
            logger = logger,
            dispatcher = dispatcher,
            processCoordinator = processCoordinator,
            isUploaderProcess = isUploaderProcess
        )
        context.selfMonitor = selfMonitor
        val monitoringExecutor = createSelfMonitoringExecutor(config, selfMonitor)
        stagedMonitoringExecutor = monitoringExecutor
        val newState = State(
            context = context,
            store = store,
            dispatcher = dispatcher,
            processCoordinator = processCoordinator,
            selfMonitorExecutor = monitoringExecutor
        )
        state = newState

        // 启动所有已注册的模块
        modules.forEach(::startModule)
        logger.d("APM initialized in process=$processName modules=${modules.size}")
        } catch (error: Exception) {
            // Publish nothing after failure and release completed stages in reverse ownership order.
            state = null
            cleanupInitializationFailure(
                monitoringExecutor = stagedMonitoringExecutor,
                coordinator = stagedCoordinator,
                dispatcher = stagedDispatcher,
                uploader = stagedUploader,
                store = stagedStore
            )
            throw error
        }
    }

    /**
     * 注册功能模块。可在 init 前或 init 后调用。
     * - init 前：加入队列，等 init 时统一启动
     * - init 后：立即初始化并启动
     * 同名模块不重复注册。
     *
     * @param module 要注册的模块实例
     */
    fun register(module: ApmModule) {
        synchronized(initLock) {
            // The duplicate check and insertion must be one atomic operation.
            if (modules.any { it.name == module.name }) {
                return
            }
            modules += module
            // 如果框架已初始化，立即启动新注册的模块
            if (state != null) {
                startModule(module)
            }
        }
    }

    /**
     * 停止 APM 框架。停止所有模块并关闭分发器。
     * 调用后框架进入未初始化状态，可重新 init。
     */
    fun stop() {
        val currentState = synchronized(initLock) {
            val active = state ?: return
            // 先切断新的事件入口，避免 stop 过程中继续接收上报。
            state = null
            active
        }
        cleanupSafely(ERROR_TAG_STOP_MONITOR) { currentState.selfMonitorExecutor?.shutdownNow() }
        cleanupSafely(ERROR_TAG_STOP_COORDINATOR) { currentState.processCoordinator?.stop() }
        currentState.startedModules.forEach { module ->
            cleanupSafely("$ERROR_TAG_STOP_MODULE_PREFIX${module.name}") { module.onStop() }
        }
        currentState.startedModules.clear()
        cleanupSafely(ERROR_TAG_STOP_DISPATCHER) { currentState.dispatcher.shutdown() }
        try {
            ApmDiagnostics.record(
                DiagnosticLevel.INFO,
                CORE_MODULE,
                EVENT_DIAGNOSTIC_SESSION_STOP,
                MESSAGE_STOPPED,
                null
            )
            ApmDiagnostics.flush(DIAGNOSTICS_FLUSH_TIMEOUT_MS)
        } finally {
            // Diagnostics closes last so cleanup degradation remains inspectable.
            ApmDiagnostics.shutdown()
        }
    }

    /**
     * 发射 APM 事件。这是模块上报数据的统一入口。
     *
     * @param module 模块名，如 "memory"、"crash"
     * @param name 事件名，如 "memory_snapshot"、"java_crash"
     * @param kind 事件类型（METRIC/ALERT/FILE）
     * @param severity 严重级别（DEBUG/INFO/WARN/ERROR/FATAL）
     * @param priority 事件优先级（LOW/NORMAL/HIGH/CRITICAL）
     * @param scene 当前场景（如 Activity 类名）
     * @param foreground 是否前台
     * @param fields 事件指标数据
     * @param extras 附加键值对
     */
    fun emit(
        module: String,
        name: String,
        kind: ApmEventKind = ApmEventKind.METRIC,
        severity: ApmSeverity = ApmSeverity.INFO,
        priority: ApmPriority = ApmPriority.NORMAL,
        scene: String? = null,
        foreground: Boolean? = null,
        fields: Map<String, Any?> = emptyMap(),
        extras: Map<String, String> = emptyMap()
    ) {
        val currentState = state ?: return
        // 只在调用线程捕获必须反映发射现场的信息：时间戳、线程名、业务上下文快照；
        // 上下文 map 合并等分配开销推迟到 dispatcher worker 线程执行
        val timestamp = System.currentTimeMillis()
        val threadName = Thread.currentThread().name
        val bizContext = captureBizContext(currentState)
        currentState.context.emitLazy {
            buildEvent(
                currentState, module, name, kind, severity, priority, scene, foreground,
                fields, extras, timestamp, threadName, bizContext
            )
        }
    }

    /**
     * Synchronously persists a critical event before process termination.
     *
     * @return true when local persistence completed
     */
    fun emitCriticalSync(
        module: String,
        name: String,
        kind: ApmEventKind = ApmEventKind.ALERT,
        severity: ApmSeverity = ApmSeverity.FATAL,
        priority: ApmPriority = ApmPriority.CRITICAL,
        scene: String? = null,
        foreground: Boolean? = null,
        fields: Map<String, Any?> = emptyMap(),
        extras: Map<String, String> = emptyMap()
    ): Boolean {
        val currentState = state ?: return false
        // critical 路径保持全同步：立即构建并持久化
        val event = buildEvent(
            currentState, module, name, kind, severity, priority, scene, foreground,
            fields, extras,
            timestamp = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            bizContext = captureBizContext(currentState)
        )
        return currentState.context.emitCriticalSync(event)
    }

    /**
     * 记录一次 SDK 内部错误。
     *
     * 供各监控模块在捕获并降级处理异常时调用，把原本"静默吞掉"的
     * 失败变为自监控计数与独立本地诊断记录，便于发现监控能力自身的退化。
     * 正常运行时同时累计健康指标；普通运行状态尚未发布但诊断运行时已建立时，
     * 仍保留结构化错误。本方法自身绝不抛出异常。
     *
     * @param tag 错误来源标签（如 "ipc_write"、"fps_frame_metrics_register"）
     * @param error 捕获到的异常，可为 null
     */
    fun recordInternalError(tag: String, error: Throwable? = null) {
        recordInternalErrorSafely(
            selfMonitorSink = { state?.context?.selfMonitor?.recordInternalError(tag) },
            diagnosticsSink = {
                // Structured diagnostics remain independent of debugLogging and the normal event pipeline.
                ApmDiagnostics.record(
                    DiagnosticLevel.ERROR,
                    CORE_MODULE,
                    tag,
                    MESSAGE_INTERNAL_ERROR,
                    error
                )
            }
        )
    }

    /** Captures an immutable host context without allowing provider failures into business code. */
    private fun captureBizContext(currentState: State): Map<String, String> {
        return captureBizContextSafely(currentState.context.config.bizContextProvider) { error ->
            recordInternalError(ERROR_TAG_BIZ_CONTEXT, error)
        }
    }

    /**
     * 读取最近的事件记录。用于 Debug 面板展示。
     *
     * @param limit 最大返回条数
     * @return line protocol 格式的事件列表，最新在前
     */
    fun recentEvents(limit: Int = 20): List<String> {
        return state?.store?.readRecent(limit).orEmpty()
    }

    /** 框架是否已初始化。 */
    fun isInitialized(): Boolean = state != null

    /**
     * 启动单个模块。调用 onInitialize → onStart。
     * 异常不会外泄，仅记录日志。
     */
    private fun startModule(module: ApmModule) {
        val currentState = state ?: return
        if (currentState.startedModules.any { it.name == module.name }) {
            return
        }
        val config = currentState.context.config
        val shouldRun = ProcessModuleFilter.shouldRunInCurrentProcess(
            moduleName = module.name,
            processName = currentState.context.processName,
            strategy = config.processStrategy,
            customMapping = config.customProcessModules
        )
        if (!shouldRun) {
            currentState.context.logger.d(
                "Skip module=${module.name} in process=${currentState.context.processName} strategy=${config.processStrategy}"
            )
            return
        }
        val dynamicEnabled = config.dynamicConfigProvider.getBoolean(
            "apm.module.${module.name}.enabled",
            true
        )
        if (!dynamicEnabled) {
            currentState.context.logger.d("Skip module=${module.name}: disabled by dynamic config")
            return
        }
        val userId = config.defaultContext[CONTEXT_USER_ID]
        val grayEnabled = config.grayController?.isEnabled(
            feature = "module.${module.name}",
            userId = userId,
            defaultValue = true
        ) ?: true
        if (!grayEnabled) {
            currentState.context.logger.d("Skip module=${module.name}: excluded by gray release")
            return
        }
        runRecoverableBoundary(
            block = {
            val moduleContext = currentState.context.withLogger(
                currentState.context.logger.withComponent(module.name)
            )
            module.onInitialize(moduleContext)
            module.onStart()
            currentState.startedModules += module
            currentState.context.logger.d("Started module=${module.name}")
            },
            onFailure = { error -> currentState.context.logger.e("Failed to start module=${module.name}", error) }
        )
    }

    /**
     * Builds an event with a consistent caller and business context snapshot.
     *
     * 时间戳、线程名与业务上下文由调用方在发射现场捕获后传入，
     * 使本方法可以安全地在 dispatcher worker 线程延迟执行
     * （map 合并的分配开销不再落在发射线程上）。
     *
     * @param timestamp 发射现场的时间戳（毫秒）
     * @param threadName 发射线程名
     * @param bizContext 发射现场的业务上下文快照
     */
    private fun buildEvent(
        currentState: State,
        module: String,
        name: String,
        kind: ApmEventKind,
        severity: ApmSeverity,
        priority: ApmPriority,
        scene: String?,
        foreground: Boolean?,
        fields: Map<String, Any?>,
        extras: Map<String, String>,
        timestamp: Long,
        threadName: String,
        bizContext: Map<String, String>
    ): ApmEvent {
        val config = currentState.context.config
        // 默认上下文与业务上下文合并（在 worker 线程执行）
        val mergedContext = config.defaultContext + bizContext
        return ApmEvent(
            module = module,
            name = name,
            kind = kind,
            severity = severity,
            priority = priority,
            timestamp = timestamp,
            processName = currentState.context.processName,
            threadName = threadName,
            scene = scene,
            foreground = foreground,
            fields = fields,
            globalContext = mergedContext,
            extras = extras
        )
    }

    /**
     * Creates periodic SDK health reporting and automatic throttling.
     */
    private fun createSelfMonitoringExecutor(config: ApmConfig, monitor: SdkSelfMonitor?): ScheduledExecutorService? {
        if (monitor == null || config.selfMonitorIntervalMs <= 0L) {
            return null
        }
        val previousDiagnosticDrops = AtomicLong(0L)
        val previousDiagnosticWriteFailures = AtomicLong(0L)
        return ApmExecutors.newSingleThreadScheduledExecutor(SELF_MONITOR_THREAD_NAME).apply {
            scheduleWithFixedDelay(
                {
                    val diagnosticsStatus = ApmDiagnostics.status()
                    val droppedDelta = (diagnosticsStatus.droppedRecords -
                        previousDiagnosticDrops.getAndSet(diagnosticsStatus.droppedRecords)).coerceAtLeast(0L)
                    val writeFailureDelta = (diagnosticsStatus.writeFailures -
                        previousDiagnosticWriteFailures.getAndSet(diagnosticsStatus.writeFailures)).coerceAtLeast(0L)
                    val report = monitor.generateReport().copy(
                        diagnosticDroppedCount = droppedDelta,
                        diagnosticWriteFailureCount = writeFailureDelta
                    )
                    emit(
                        module = CORE_MODULE,
                        name = EVENT_SDK_HEALTH,
                        severity = ApmSeverity.INFO,
                        priority = ApmPriority.LOW,
                        fields = report.toCoreHealthFields()
                    )
                    if (config.enableAutoThrottle) {
                        applyAutoThrottle(AutoThrottle.computeModulesToDisable(report))
                    }
                },
                config.selfMonitorIntervalMs,
                config.selfMonitorIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * Stops modules selected by the automatic health policy.
     *
     * @param moduleNames module names to stop
     */
    private fun applyAutoThrottle(moduleNames: List<String>) {
        val currentState = state ?: return
        for (module in currentState.startedModules.toList()) {
            if (module.name !in moduleNames) {
                continue
            }
            val stopped = runRecoverableBoundary(
                block = { module.onStop() },
                onFailure = { error ->
                    currentState.context.logger.e("Failed to auto-throttle module=${module.name}", error)
                }
            )
            if (stopped) {
                currentState.startedModules.remove(module)
                currentState.context.logger.w("Auto-throttled module=${module.name}")
            }
        }
    }

    /** Releases partially initialized resources in reverse ownership order. */
    private fun cleanupInitializationFailure(
        monitoringExecutor: ScheduledExecutorService?,
        coordinator: ProcessEventCoordinator?,
        dispatcher: ApmDispatcher?,
        uploader: ApmUploader?,
        store: EventStore?
    ) {
        cleanupSafely(ERROR_TAG_INIT_MONITOR) { monitoringExecutor?.shutdownNow() }
        cleanupSafely(ERROR_TAG_INIT_COORDINATOR) { coordinator?.stop() }
        if (dispatcher != null) {
            cleanupSafely(ERROR_TAG_INIT_DISPATCHER) { dispatcher.shutdown() }
        } else {
            // Before dispatcher ownership transfers, uploader and store remain independent stages.
            cleanupSafely(ERROR_TAG_INIT_UPLOADER) { uploader?.shutdown() }
            cleanupSafely(ERROR_TAG_INIT_STORE) { store?.close() }
        }
    }

    /** Runs one cleanup phase without preventing later phases from executing. */
    private inline fun cleanupSafely(tag: String, block: () -> Unit) {
        runRecoverableBoundary(block) { error ->
            // Cleanup degradation is routed directly to the independent diagnostics path.
            recordInternalError(tag, error)
        }
    }

    /** 框架运行时状态。 */
    private data class State(
        /** APM 上下文。 */
        val context: ApmContext,
        /** 本地存储。 */
        val store: EventStore,
        /** 事件分发器。 */
        val dispatcher: ApmDispatcher,
        /** Cross-process event coordinator. */
        val processCoordinator: ProcessEventCoordinator?,
        /** SDK self-monitor scheduler. */
        val selfMonitorExecutor: ScheduledExecutorService?,
        /** Modules that completed initialization and start successfully. */
        val startedModules: CopyOnWriteArraySet<ApmModule> = CopyOnWriteArraySet()
    )

    /** SDK self-monitor thread name. */
    private const val SELF_MONITOR_THREAD_NAME = "apm-self-monitor"

    /** Core module name used by health events. */
    private const val CORE_MODULE = "core"

    /** Uploader logger component. */
    private const val UPLOADER_MODULE = "uploader"

    /** Aggregation logger component. */
    private const val AGGREGATION_MODULE = "aggregation"

    /** Privacy logger component. */
    private const val PRIVACY_MODULE = "privacy"

    /** Dispatcher logger component. */
    private const val DISPATCHER_MODULE = "dispatcher"

    /** SDK health event name. */
    private const val EVENT_SDK_HEALTH = "sdk_health"

    /** Diagnostic code for SDK session start. */
    private const val EVENT_DIAGNOSTIC_SESSION_START = "session_start"

    /** Diagnostic code for SDK session stop. */
    private const val EVENT_DIAGNOSTIC_SESSION_STOP = "session_stop"

    /** Diagnostic code for initialization failure. */
    private const val ERROR_CODE_INIT = "init_failed"

    /** Self-monitoring tag for a host business-context provider failure. */
    private const val ERROR_TAG_BIZ_CONTEXT = "biz_context_provider"

    /** Safe initialization-start message. */
    private const val MESSAGE_INIT_STARTED = "APM initialization started"

    /** Safe initialization-failure message. */
    private const val MESSAGE_INIT_FAILED = "APM initialization failed"

    /** Safe internal-error message. */
    private const val MESSAGE_INTERNAL_ERROR = "Internal SDK error"

    /** Safe shutdown message. */
    private const val MESSAGE_STOPPED = "APM stopped"

    /** Bounded diagnostics flush during init failure and normal shutdown. */
    private const val DIAGNOSTICS_FLUSH_TIMEOUT_MS = 1_000L

    /** Conventional context key used for stable gray-release assignment. */
    private const val CONTEXT_USER_ID = "userId"

    /** Init rollback tag for the self-monitor executor. */
    private const val ERROR_TAG_INIT_MONITOR = "init_cleanup_monitor"
    /** Init rollback tag for the process coordinator. */
    private const val ERROR_TAG_INIT_COORDINATOR = "init_cleanup_coordinator"
    /** Init rollback tag for the dispatcher. */
    private const val ERROR_TAG_INIT_DISPATCHER = "init_cleanup_dispatcher"
    /** Init rollback tag for the uploader. */
    private const val ERROR_TAG_INIT_UPLOADER = "init_cleanup_uploader"
    /** Init rollback tag for the event store. */
    private const val ERROR_TAG_INIT_STORE = "init_cleanup_store"
    /** Shutdown tag for the self-monitor executor. */
    private const val ERROR_TAG_STOP_MONITOR = "stop_monitor"
    /** Shutdown tag for the process coordinator. */
    private const val ERROR_TAG_STOP_COORDINATOR = "stop_coordinator"
    /** Shutdown tag prefix for monitoring modules. */
    private const val ERROR_TAG_STOP_MODULE_PREFIX = "stop_module_"
    /** Shutdown tag for the dispatcher. */
    private const val ERROR_TAG_STOP_DISPATCHER = "stop_dispatcher"
}
