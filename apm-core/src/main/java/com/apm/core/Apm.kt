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
import com.apm.uploader.ApmUploader
import java.util.concurrent.CopyOnWriteArrayList

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
            StorageType.FILE -> FileEventStore(application)
        }

        // 上传通道：优先使用显式自定义 uploader，其次按 endpoint 自动推导。
        val uploader: ApmUploader = UploaderFactory.create(config)

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

        // 组装分发器和上下文
        val dispatcher = ApmDispatcher(store, uploader, logger, rateLimiter, aggregator, piiSanitizer)
        val context = ApmContext(
            application = application,
            config = config,
            processName = processName,
            logger = logger,
            dispatcher = dispatcher
        )
        state = State(context, store, dispatcher, uploader)

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
        // 防止同名模块重复注册
        if (modules.any { it.name == module.name }) return
        modules += module
        // 如果框架已初始化，立即启动新注册的模块
        if (state != null) {
            startModule(module)
        }
    }

    /**
     * 停止 APM 框架。停止所有模块并关闭分发器。
     * 调用后框架进入未初始化状态，可重新 init。
     */
    fun stop() {
        val currentState = state ?: return
        // 先切断新的事件入口，避免 stop 过程中继续接收上报。
        state = null
        modules.forEach {
            runCatching { it.onStop() }
        }
        currentState.dispatcher.shutdown()
        currentState.uploader.shutdown()
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
        val config = currentState.context.config
        // 合并静态上下文 + 业务动态上下文
        val mergedContext = config.defaultContext + config.bizContextProvider.currentContext()
        // 在调用线程捕获线程名，避免在 dispatcher 线程中丢失原始调用方信息
        val callerThread = Thread.currentThread().name
        currentState.context.emit(
            ApmEvent(
                module = module,
                name = name,
                kind = kind,
                severity = severity,
                priority = priority,
                processName = currentState.context.processName,
                threadName = callerThread,
                scene = scene,
                foreground = foreground,
                fields = fields,
                globalContext = mergedContext,
                extras = extras
            )
        )
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
        runCatching {
            module.onInitialize(currentState.context)
            module.onStart()
            currentState.context.logger.d("Started module=${module.name}")
        }.onFailure {
            currentState.context.logger.e("Failed to start module=${module.name}", it)
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
        /** 上传器。 */
        val uploader: ApmUploader
    )
}
