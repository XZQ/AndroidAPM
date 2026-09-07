package com.apm.core

import com.apm.core.throttle.DynamicConfigProvider
import com.apm.core.throttle.GrayReleaseController
import com.apm.core.diagnostics.DiagnosticsConfig
import com.apm.model.ApmResourceContext
import com.apm.model.ApmOccurrenceContext
import com.apm.model.SerializationFormat
import com.apm.uploader.ApmUploader
import com.apm.uploader.HttpHeaderProvider
import com.apm.uploader.LogcatApmUploader
import java.net.URI

/** 进程策略：控制 APM 在哪些进程中初始化。 */
enum class ProcessStrategy {
    /** 仅在主进程初始化，子进程跳过。 */
    MAIN_PROCESS_ONLY,
    /** 所有进程都初始化。 */
    ALL_PROCESSES,
    /** 自定义进程映射：通过 customProcessModules 指定每个进程启用的模块。 */
    CUSTOM
}

/** 事件存储类型：FILE（ring buffer）或 SQLITE（持久化，50K 容量）。 */
enum class StorageType {
    /** 文件存储：500 行 ring buffer + 文件。 */
    FILE,
    /** SQLite 存储：50,000 条容量，按优先级淘汰，WAL 模式。 */
    SQLITE
}

/** Runtime safety profile applied before SDK infrastructure is created. */
enum class ApmRuntimeProfile {
    /** Backward-compatible behavior for development and existing integrations. */
    COMPATIBILITY,

    /** Fail-closed production behavior requiring durable, private, authenticated delivery. */
    PRODUCTION_STRICT
}

/** Host-declared collection consent used by strict production initialization. */
enum class CollectionConsent {
    /** Compatibility value for integrations that have not adopted explicit consent declaration. */
    UNSPECIFIED,

    /** The host has obtained consent for this SDK initialization. */
    GRANTED,

    /** Collection is not permitted and SDK initialization must fail closed. */
    DENIED
}

/**
 * 业务上下文提供者。每次事件上报时调用，将业务动态信息注入事件。
 * 例如：当前用户 ID、设备 ID、AB 实验分组等。
 */
fun interface BizContextProvider {
    /**
     * 返回当前业务上下文键值对，会被合并到每条事件的 globalContext 中。
     *
     * [BizContextCaptureMode.SYNCHRONOUS] 下该方法运行在 emit 调用线程，必须是 O(1)、
     * 无 IO、无等待锁；可能阻塞的实现应使用 [BizContextCaptureMode.ASYNC_CACHED]。
     */
    fun currentContext(): Map<String, String>

    companion object {
        /** 空实现，不注入任何业务上下文。 */
        val EMPTY = BizContextProvider { emptyMap() }
    }
}

/**
 * APM 框架全局配置。
 * 在 [Apm.init] 时传入，初始化后不可修改。
 */
data class ApmConfig(
    /** 上传目标地址。为空时安全确认并丢弃；本地输出必须显式使用 `logcat://`。 */
    val endpoint: String = "",
    /** 可选自定义上传器。非空时优先级高于 endpoint 自动推导。 */
    val uploader: ApmUploader? = null,
    /** 是否开启调试日志（Log.d 级别）；默认关闭，避免生产构建意外输出调试信息。 */
    val debugLogging: Boolean = false,
    /** 独立 SDK 自诊断日志配置。 */
    val diagnostics: DiagnosticsConfig = DiagnosticsConfig(),
    /** 进程策略：控制 APM 在哪些进程中初始化。 */
    val processStrategy: ProcessStrategy = ProcessStrategy.MAIN_PROCESS_ONLY,
    /** 自定义进程模块映射：进程名 → 允许运行的模块名列表。仅 [ProcessStrategy.CUSTOM] 时生效。 */
    val customProcessModules: Map<String, List<String>> = emptyMap(),
    /** 事件存储类型：FILE（ring buffer 500 行）或 SQLITE（50,000 条，生产推荐）。 */
    val storageType: StorageType = StorageType.SQLITE,
    /** SQLite 单事件持久化 payload 软上限；超限事件单独拒绝，不影响同批其他事件。 */
    val maxEventPayloadBytes: Int = DEFAULT_MAX_EVENT_PAYLOAD_BYTES,
    /** SQLite 活跃 payload 总量预算；超限时按低优先级、旧事件优先淘汰。 */
    val maxStoredPayloadBytes: Long = DEFAULT_MAX_STORED_PAYLOAD_BYTES,
    /** Dispatcher queued-event retained-byte budget in addition to the fixed event count. */
    val maxDispatcherQueueBytes: Long = DEFAULT_MAX_DISPATCHER_QUEUE_BYTES,
    /** Non-uploader process pending-event byte budget before IPC serialization. */
    val maxIpcPendingBytes: Long = DEFAULT_MAX_IPC_PENDING_BYTES,
    /** Maximum bytes in one atomically published IPC hand-off file. */
    val maxIpcFileBytes: Long = DEFAULT_MAX_IPC_FILE_BYTES,
    /** Maximum published IPC bytes retained across all SDK hand-off files. */
    val maxIpcDirectoryBytes: Long = DEFAULT_MAX_IPC_DIRECTORY_BYTES,
    /** 默认上下文，初始化时传入的静态键值对，每条事件都会携带。 */
    val defaultContext: Map<String, String> = emptyMap(),
    /** 业务上下文提供者；调用位置和新鲜度由 [bizContextCaptureMode] 控制。 */
    val bizContextProvider: BizContextProvider = BizContextProvider.EMPTY,

    // --- Phase 5: 限流 ---
    /** 每个时间窗口内允许通过的最大事件数（按 module/name 分桶）。 */
    val rateLimitEventsPerWindow: Int = DEFAULT_RATE_LIMIT_EVENTS,
    /** 限流窗口时长（毫秒）。 */
    val rateLimitWindowMs: Long = DEFAULT_RATE_LIMIT_WINDOW_MS,

    // --- Phase 5: 灰度 ---
    /** 动态配置提供者，对接远程配置中心（Apollo / Firebase 等）。 */
    val dynamicConfigProvider: DynamicConfigProvider = DynamicConfigProvider.NOOP,
    /** 灰度发布控制器，按版本/用户/百分比控制功能开关。 */
    val grayController: GrayReleaseController? = null,

    // --- Phase 8: 序列化 ---
    /** Wire encoding; strict default HTTP delivery requires the versioned protobuf envelope. */
    val serializationFormat: SerializationFormat = SerializationFormat.LINE_PROTOCOL,

    // --- Phase 8: 聚合 ---
    /** 是否启用客户端事件聚合（高频 METRIC 滑动窗口 + ALERT 栈指纹去重）。 */
    val enableAggregation: Boolean = false,
    /** 聚合窗口时长（毫秒），默认 5 分钟。 */
    val aggregationWindowMs: Long = DEFAULT_AGGREGATION_WINDOW_MS,

    // --- Phase 8: PII 脱敏 ---
    /** 是否启用 PII 脱敏；默认开启，确需保留原文时必须由接入方显式关闭并完成隐私评审。 */
    val enablePiiSanitization: Boolean = true,
    /** 自定义脱敏规则（追加到内置规则之后）。 */
    val customSanitizationRules: List<com.apm.core.privacy.SanitizationRule> = emptyList(),

    // --- Phase 5: 重试 ---
    /** 是否开启上传重试（指数退避）。 */
    val enableRetry: Boolean = true,
    /** 最大重试次数。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 重试基础延迟（毫秒），第一次重试等待该值，后续按倍率递增。 */
    val retryBaseDelayMs: Long = DEFAULT_RETRY_BASE_DELAY_MS,
    /** Persistent or in-memory upload batch size. */
    val uploadBatchSize: Int = DEFAULT_UPLOAD_BATCH_SIZE,
    /** Enables SDK health reporting. */
    val enableSelfMonitoring: Boolean = true,
    /** SDK health report interval. */
    val selfMonitorIntervalMs: Long = DEFAULT_SELF_MONITOR_INTERVAL_MS,
    /** Enables automatic module throttling when SDK health degrades. */
    val enableAutoThrottle: Boolean = true,
    /** Enables file-based cross-process event forwarding. */
    val enableMultiProcessCoordination: Boolean = false,
    /** 默认 HTTP uploader 是否启用 Gzip 压缩。 */
    val enableHttpGzip: Boolean = true,
    /** 是否允许动态配置通过 `apm.upload.endpoint` 覆盖内置 HTTPS 地址。 */
    val enableDynamicHttpEndpoint: Boolean = false,
    /** 默认 HTTP uploader 的静态请求头；不得放入长期密钥。 */
    val httpHeaders: Map<String, String> = emptyMap(),
    /** 默认 HTTP uploader 每次请求调用的动态 Header 提供者，用于短期 Token。 */
    val httpHeaderProvider: HttpHeaderProvider = HttpHeaderProvider.EMPTY,
    /** Durable row lease duration for one upload attempt. */
    val uploadLeaseDurationMs: Long = DEFAULT_UPLOAD_LEASE_DURATION_MS,
    /** 是否在 dispatcher 高水位限制单一 NORMAL/LOW 模块占满共享队列。 */
    val enableDispatcherModuleIsolation: Boolean = true,
    /** 启动模块占用隔离的队列水位百分比；运行时约束到 1..100。 */
    val dispatcherIsolationHighWatermarkPercent: Int = DEFAULT_DISPATCHER_HIGH_WATERMARK_PERCENT,
    /** 单模块允许占用的队列百分比；运行时不超过高水位百分比。 */
    val dispatcherMaxModuleQueueSharePercent: Int = DEFAULT_DISPATCHER_MAX_MODULE_SHARE_PERCENT,
    /** 业务上下文捕获模式；默认同步以保持现有事件时刻语义。 */
    val bizContextCaptureMode: BizContextCaptureMode = BizContextCaptureMode.SYNCHRONOUS,
    /** 异步缓存模式的 provider 刷新间隔；运行时约束到 100 ms..24 h。 */
    val bizContextRefreshIntervalMs: Long = DEFAULT_BIZ_CONTEXT_REFRESH_INTERVAL_MS,
    /** Runtime safety profile; strict production is opt-in for source compatibility. */
    val runtimeProfile: ApmRuntimeProfile = ApmRuntimeProfile.COMPATIBILITY,
    /** Consent state captured by the host before this initialization attempt. */
    val initialCollectionConsent: CollectionConsent = CollectionConsent.UNSPECIFIED,
    /** Standard batch-level service/release/environment/anonymous-installation identity. */
    val resourceContext: ApmResourceContext = ApmResourceContext(),
    /** Maximum uncompressed protobuf envelope bytes in one versioned HTTP request. */
    val maxUploadBatchBytes: Int = com.apm.uploader.HttpApmUploader.DEFAULT_MAX_BATCH_BYTES
) {
    companion object {
        /** 默认限流：每窗口 10 条事件。 */
        private const val DEFAULT_RATE_LIMIT_EVENTS = 10
        /** 默认限流窗口：60 秒。 */
        private const val DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000L
        /** 默认最大重试次数。 */
        private const val DEFAULT_MAX_RETRIES = 3
        /** 默认重试基础延迟：1 秒。 */
        private const val DEFAULT_RETRY_BASE_DELAY_MS = 1000L

        /** Default number of events per upload request. */
        private const val DEFAULT_UPLOAD_BATCH_SIZE = 20

        /** Default lease duration, longer than the built-in HTTP request timeout. */
        private const val DEFAULT_UPLOAD_LEASE_DURATION_MS = 120_000L

        /** Default SDK health report interval. */
        private const val DEFAULT_SELF_MONITOR_INTERVAL_MS = 60_000L

        /** Default per-event durable payload soft limit: 256 KiB. */
        private const val DEFAULT_MAX_EVENT_PAYLOAD_BYTES = 256 * 1024

        /** Default live SQLite payload budget: 64 MiB. */
        private const val DEFAULT_MAX_STORED_PAYLOAD_BYTES = 64L * 1024L * 1024L

        /** Default process-local dispatcher retention budget: 8 MiB. */
        private const val DEFAULT_MAX_DISPATCHER_QUEUE_BYTES = 8L * 1024L * 1024L

        /** Default non-uploader pending-event retention budget: 4 MiB. */
        private const val DEFAULT_MAX_IPC_PENDING_BYTES = 4L * 1024L * 1024L

        /** Default atomic IPC file budget: 1 MiB. */
        private const val DEFAULT_MAX_IPC_FILE_BYTES = 1L * 1024L * 1024L

        /** Default total published IPC directory budget: 16 MiB. */
        private const val DEFAULT_MAX_IPC_DIRECTORY_BYTES = 16L * 1024L * 1024L

        /** Default queue pressure level that activates noisy-module isolation. */
        private const val DEFAULT_DISPATCHER_HIGH_WATERMARK_PERCENT = 75

        /** Default maximum queue share reserved for one NORMAL/LOW module under pressure. */
        private const val DEFAULT_DISPATCHER_MAX_MODULE_SHARE_PERCENT = 50

        /** Default asynchronous business-context refresh interval: one second. */
        private const val DEFAULT_BIZ_CONTEXT_REFRESH_INTERVAL_MS = 1_000L

        /** 默认聚合窗口：5 分钟。 */
        private const val DEFAULT_AGGREGATION_WINDOW_MS = 300_000L
    }
}

/**
 * Validates fail-closed runtime invariants before diagnostics, storage, or module threads start.
 *
 * Compatibility mode preserves existing endpoint and privacy switches. Strict production requires
 * explicit consent, durable storage, mandatory sanitization, disabled debug logging, and either a
 * verified HTTPS endpoint or a non-Logcat custom uploader.
 */
internal fun ApmConfig.validateForRuntime(occurrenceContext: ApmOccurrenceContext? = null) {
    require(initialCollectionConsent != CollectionConsent.DENIED) {
        "APM collection consent is denied"
    }
    if (serializationFormat == SerializationFormat.PROTOBUF_ENVELOPE_V3) {
        require(occurrenceContext?.hasStrictOccurrenceIdentity() == true) {
            "PROTOBUF_ENVELOPE_V3 requires occurrence-bound version, build, variant, and installation identity"
        }
    }
    if (runtimeProfile != ApmRuntimeProfile.PRODUCTION_STRICT) {
        return
    }
    require(initialCollectionConsent == CollectionConsent.GRANTED) {
        "PRODUCTION_STRICT requires explicit GRANTED collection consent"
    }
    require(!debugLogging) {
        "PRODUCTION_STRICT forbids debug logging"
    }
    require(enablePiiSanitization) {
        "PRODUCTION_STRICT requires PII sanitization"
    }
    require(storageType == StorageType.SQLITE) {
        "PRODUCTION_STRICT requires the durable SQLite outbox"
    }
    require(uploader !is LogcatApmUploader) {
        "PRODUCTION_STRICT forbids Logcat event delivery"
    }
    require(uploader != null || endpoint.isStrictHttpsEndpoint()) {
        "PRODUCTION_STRICT requires an HTTPS endpoint or a custom uploader"
    }
    if (uploader == null) {
        require(serializationFormat == SerializationFormat.PROTOBUF_ENVELOPE_V3) {
            "PRODUCTION_STRICT default HTTP delivery requires PROTOBUF_ENVELOPE_V3"
        }
        require(resourceContext.hasStrictResourceIdentity()) {
            "PRODUCTION_STRICT requires bounded service, version, environment, and installation resource identity"
        }
        require(maxUploadBatchBytes in MIN_STRICT_BATCH_BYTES..com.apm.uploader.HttpApmUploader.MAX_BATCH_BYTES) {
            "PRODUCTION_STRICT maxUploadBatchBytes is outside the supported range"
        }
        require(maxEventPayloadBytes > 0 &&
            maxUploadBatchBytes.toLong() >= maxEventPayloadBytes.toLong() + MIN_ENVELOPE_HEADROOM_BYTES
        ) {
            "PRODUCTION_STRICT maxUploadBatchBytes must leave envelope headroom above maxEventPayloadBytes"
        }
    }
}

/** Returns whether all fixed production resource values are non-blank, trimmed, and bounded. */
private fun ApmResourceContext.hasStrictResourceIdentity(): Boolean {
    return serviceName.isStrictResourceValue() &&
        serviceVersion.isStrictResourceValue() &&
        deploymentEnvironment.isStrictResourceValue() &&
        installationId.isStrictResourceValue()
}

/** Returns whether the per-event identity is complete and safe to freeze into durable rows. */
private fun ApmOccurrenceContext.hasStrictOccurrenceIdentity(): Boolean {
    if (!serviceVersion.isStrictResourceValue() ||
        !versionCode.isCanonicalVersionCode() ||
        !appBuild.isStrictResourceValue() ||
        !variant.isStrictResourceValue() ||
        !installationId.isStrictResourceValue() ||
        nativeFrames.size > MAX_OCCURRENCE_NATIVE_FRAMES
    ) {
        return false
    }
    return nativeFrames.all { frame ->
        val loadBias = frame.loadBias
        frame.abi.isStrictResourceValue() &&
            frame.moduleBuildId.isStrictResourceValue() &&
            frame.moduleName.isStrictResourceValue() &&
            frame.moduleRelativePc >= 0L &&
            (loadBias == null || loadBias >= 0L)
    }
}

/** Returns whether versionCode is canonical unsigned decimal text within the identity bound. */
private fun String.isCanonicalVersionCode(): Boolean {
    return isStrictResourceValue() && all(Char::isDigit) && (length == 1 || first() != '0')
}

/** Applies the fixed resource-value bound before any protobuf allocation occurs. */
private fun String.isStrictResourceValue(): Boolean {
    return isNotBlank() && this == trim() && length <= MAX_RESOURCE_VALUE_CHARS
}

/** Returns whether this exact endpoint is an HTTPS URL without embedded credentials. */
private fun String.isStrictHttpsEndpoint(): Boolean {
    if (isBlank() || this != trim()) {
        return false
    }
    return try {
        val parsed = URI(this)
        parsed.scheme.equals(STRICT_HTTPS_SCHEME, ignoreCase = true) &&
            !parsed.host.isNullOrBlank() &&
            parsed.userInfo == null
    } catch (_: Exception) {
        false
    }
}

/** HTTPS scheme required by strict production delivery. */
private const val STRICT_HTTPS_SCHEME = "https"

/** Maximum UTF-16 characters in each strict standard resource value. */
private const val MAX_RESOURCE_VALUE_CHARS = 256

/** Maximum native frames admitted into one strict occurrence snapshot. */
private const val MAX_OCCURRENCE_NATIVE_FRAMES = 256

/** Minimum strict request budget: 64 KiB. */
private const val MIN_STRICT_BATCH_BYTES = 64 * 1024

/** Reserved protobuf/resource/header growth above the configured durable event soft limit. */
private const val MIN_ENVELOPE_HEADROOM_BYTES = 16 * 1024L

/** Controls where and when [BizContextProvider] is evaluated. */
enum class BizContextCaptureMode {
    /**
     * Captures an exact occurrence-time snapshot on the emit caller.
     *
     * This compatibility mode requires an O(1), non-blocking provider with no IO or contended locks.
     */
    SYNCHRONOUS,

    /**
     * Refreshes the provider on an SDK background executor and reads the last good snapshot on emit.
     *
     * Emit never calls the provider, but context can be stale by one refresh interval and is empty
     * until the first successful refresh.
     */
    ASYNC_CACHED
}

/**
 * Freezes collection-valued configuration before it is shared with background workers.
 *
 * Provider and uploader objects intentionally keep their identity because they are runtime
 * collaborators. Maps and lists are copied so later host mutation cannot rewrite event context,
 * process routing, headers, or sanitization rules after initialization.
 */
internal fun ApmConfig.snapshotForRuntime(): ApmConfig = copy(
    customProcessModules = customProcessModules.mapValues { (_, modules) -> modules.toList() }.toMap(),
    defaultContext = defaultContext.toMap(),
    customSanitizationRules = customSanitizationRules.toList(),
    httpHeaders = httpHeaders.toMap()
)
