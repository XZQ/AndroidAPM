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
import com.apm.uploader.ApmUploader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    fun init(
        application: Application,
        config: ApmConfig
    ) {
        synchronized(initLock) {
            if (state != null) return
            doInit(application, config)
        }
    }

    /**
     * 实际初始化逻辑。已由外部 synchronized 保证线程安全。
     */
    private fun doInit(application: Application, config: ApmConfig) {
        val logger = AndroidApmLogger(config.debugLogging)
        val processName = application.currentProcessNameCompat()

        // 根据进程策略决定是否跳过非主进程
        if (config.processStrategy == ProcessStrategy.MAIN_PROCESS_ONLY &&
            !application.isMainProcessCompat()
        ) {
            logger.d("Skip init in non-main process: $processName")
            return
        }

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

        // 上传通道：优先使用显式自定义 uploader，其次按 endpoint 自动推导。
        val uploader: ApmUploader = UploaderFactory.create(
            config = config,
            durableStore = store is com.apm.storage.PendingEventStore,
            logger = logger
        )

        // 限流器：按 module/name 分桶，超出配额的事件被丢弃
        val rateLimiter = if (config.rateLimitEventsPerWindow > 0) {
            RateLimiter(config.rateLimitEventsPerWindow, config.rateLimitWindowMs)
        } else null

        // 聚合器：高频 METRIC 事件滑动窗口聚合 + ALERT 栈指纹去重
        val aggregator = if (config.enableAggregation) {
            EventAggregator(
                windowMs = config.aggregationWindowMs,
                enabled = true,
                logger = logger
            )
        } else null

        // PII 脱敏器：上报前自动去除手机号/邮箱/身份证/敏感URL参数
        val piiSanitizer = if (config.enablePiiSanitization) {
            PiiSanitizer(
                rules = DefaultSanitizationRules.all() + config.customSanitizationRules,
                logger = logger
            )
        } else null

        // SDK self monitoring is wired before worker construction so queue and
        // upload metrics include restart replay from the first cycle.
        val selfMonitor = if (config.enableSelfMonitoring) SdkSelfMonitor() else null

        // 组装分发器和上下文
        val dispatcher = ApmDispatcher(
            store = store,
            uploader = uploader,
            logger = logger,
            rateLimiter = rateLimiter,
            aggregator = aggregator,
            piiSanitizer = piiSanitizer,
            selfMonitor = selfMonitor,
            retryPolicy = com.apm.uploader.RetryPolicy(
                maxRetries = if (config.enableRetry) config.maxRetries else 0,
                baseDelayMs = config.retryBaseDelayMs
            ),
            uploadBatchSize = config.uploadBatchSize
        )
        val isUploaderProcess = application.isMainProcessCompat()
        val processCoordinator = if (config.enableMultiProcessCoordination) {
            ProcessEventCoordinator(application, isUploaderProcess).apply {
                onRemoteEvent = dispatcher::dispatch
                start()
            }
        } else {
            null
        }
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
            if (modules.any { it.name == module.name }) return
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
        currentState.selfMonitorExecutor?.shutdownNow()
        currentState.processCoordinator?.stop()
        currentState.startedModules.forEach {
            runCatching { it.onStop() }
        }
        currentState.startedModules.clear()
        currentState.dispatcher.shutdown()
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
        currentState.context.emit(
            buildEvent(currentState, module, name, kind, severity, priority, scene, foreground, fields, extras)
        )
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
        val event = buildEvent(
            currentState, module, name, kind, severity, priority, scene, foreground, fields, extras
        )
        return currentState.context.emitCriticalSync(event)
    }

    /**
     * 记录一次 SDK 内部错误。
     *
     * 供各监控模块在捕获并降级处理异常时调用，把原本"静默吞掉"的
     * 失败变为自监控计数与调试日志，便于发现监控能力自身的退化。
     * 未初始化时安静忽略；本方法自身绝不抛出异常。
     *
     * @param tag 错误来源标签（如 "ipc_write"、"fps_frame_metrics_register"）
     * @param error 捕获到的异常，可为 null
     */
    fun recordInternalError(tag: String, error: Throwable? = null) {
        // 未初始化（如纯 JVM 单测直接构造组件）时为无害 no-op
        val currentState = state ?: return
        currentState.context.selfMonitor?.recordInternalError(tag)
        // 调试日志受 debugLogging 开关门控，线上默认静默
        currentState.context.logger.d("Internal error [$tag]: $error")
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
        if (currentState.startedModules.any { it.name == module.name }) return
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
        runCatching {
            module.onInitialize(currentState.context)
            module.onStart()
            currentState.startedModules += module
            currentState.context.logger.d("Started module=${module.name}")
        }.onFailure {
            currentState.context.logger.e("Failed to start module=${module.name}", it)
        }
    }

    /**
     * Builds an event with a consistent caller and business context snapshot.
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
        extras: Map<String, String>
    ): ApmEvent {
        val config = currentState.context.config
        val mergedContext = config.defaultContext + config.bizContextProvider.currentContext()
        return ApmEvent(
            module = module,
            name = name,
            kind = kind,
            severity = severity,
            priority = priority,
            processName = currentState.context.processName,
            threadName = Thread.currentThread().name,
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
    private fun createSelfMonitoringExecutor(
        config: ApmConfig,
        monitor: SdkSelfMonitor?
    ): ScheduledExecutorService? {
        if (monitor == null || config.selfMonitorIntervalMs <= 0L) return null
        return Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, SELF_MONITOR_THREAD_NAME)
        }.apply {
            scheduleWithFixedDelay(
                {
                    val report = monitor.generateReport()
                    emit(
                        module = CORE_MODULE,
                        name = EVENT_SDK_HEALTH,
                        severity = ApmSeverity.INFO,
                        priority = ApmPriority.LOW,
                        fields = mapOf(
                            FIELD_EMIT_COUNT to report.emitCount,
                            FIELD_DROP_COUNT to report.dropCount,
                            FIELD_DROP_RATE to report.dropRate,
                            FIELD_QUEUE_SIZE to report.queueSize,
                            FIELD_AVG_UPLOAD_LATENCY to report.avgUploadLatencyMs,
                            FIELD_MAX_UPLOAD_LATENCY to report.maxUploadLatencyMs
                        )
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
            if (module.name !in moduleNames) continue
            runCatching { module.onStop() }
                .onSuccess {
                    currentState.startedModules.remove(module)
                    currentState.context.logger.w("Auto-throttled module=${module.name}")
                }
                .onFailure {
                    currentState.context.logger.e("Failed to auto-throttle module=${module.name}", it)
                }
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

    /** SDK health event name. */
    private const val EVENT_SDK_HEALTH = "sdk_health"

    /** Health field: emitted events. */
    private const val FIELD_EMIT_COUNT = "emitCount"

    /** Health field: dropped events. */
    private const val FIELD_DROP_COUNT = "dropCount"

    /** Health field: drop ratio. */
    private const val FIELD_DROP_RATE = "dropRate"

    /** Health field: durable or in-memory queue size. */
    private const val FIELD_QUEUE_SIZE = "queueSize"

    /** Health field: average upload latency. */
    private const val FIELD_AVG_UPLOAD_LATENCY = "avgUploadLatencyMs"

    /** Health field: maximum upload latency. */
    private const val FIELD_MAX_UPLOAD_LATENCY = "maxUploadLatencyMs"

    /** Conventional context key used for stable gray-release assignment. */
    private const val CONTEXT_USER_ID = "userId"
}
