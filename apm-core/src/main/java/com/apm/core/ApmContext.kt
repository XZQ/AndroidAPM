package com.apm.core

import android.app.Application
import com.apm.core.diagnostics.HostIntegrationPoint
import com.apm.core.diagnostics.HostIntegrationRegistry
import com.apm.model.ApmEvent
import com.apm.model.ApmOccurrenceContext
import com.apm.model.ApmPriority
import com.apm.model.SerializationFormat
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor

/**
 * APM 模块运行上下文。
 * 在 [ApmModule.onInitialize] 时注入，提供模块所需的框架能力。
 * 模块通过 [emit] 方法将事件送入统一分发通道。
 */
class ApmContext internal constructor(
    /** 宿主 Application 实例。 */
    val application: Application,
    /** APM 全局配置。 */
    val config: ApmConfig,
    /** 当前进程名。 */
    val processName: String,
    /** 日志接口。 */
    val logger: ApmLogger,
    /** 事件分发器，内部使用。 */
    private val dispatcher: ApmDispatcher,
    /** Optional cross-process event bridge. */
    private val processCoordinator: ProcessEventCoordinator? = null,
    /** Whether the current process owns network upload. */
    private val isUploaderProcess: Boolean = true,
    /** Host occurrence identity frozen once by the occurrence-aware [Apm.init] overload. */
    private val occurrenceContext: ApmOccurrenceContext? = null
) {
    /** SDK 自监控组件，用于模块记录自身运行指标。 */
    var selfMonitor: SdkSelfMonitor? = null

    /**
     * 发送事件到 APM 分发通道。
     * 事件会经过限流检查 → 本地存储 → 上传。
     *
     * @param event 已构造完成的 APM 事件
     */
    fun emit(event: ApmEvent) {
        val occurrenceBoundEvent = bindOccurrence(event)
        if (processCoordinator != null && !isUploaderProcess) {
            processCoordinator.writeEvent(occurrenceBoundEvent)
        } else {
            dispatcher.dispatch(occurrenceBoundEvent)
        }
    }

    /**
     * 发送延迟构建的事件到 APM 分发通道。
     * 主进程路径把事件构建推迟到 dispatcher worker 线程，降低发射线程开销；
     * 子进程 IPC 路径需要完整事件内容，立即构建。
     *
     * @param priority 入队前已知的事件优先级
     * @param sourceModule 入队前已知的来源模块，用于共享队列的 noisy-neighbor 隔离
     * @param estimatedBytes conservative retained-byte reservation for dispatcher admission
     * @param eventFactory 事件构建工厂（纯函数，可在任意线程执行）
     */
    internal fun emitLazy(
        priority: ApmPriority,
        sourceModule: String,
        estimatedBytes: Long,
        eventFactory: () -> ApmEvent
    ) {
        if (processCoordinator != null && !isUploaderProcess) {
            // IPC 写文件需要完整事件，立即构建
            processCoordinator.writeEvent(bindOccurrence(eventFactory()))
        } else {
            dispatcher.dispatchLazy(priority, sourceModule, estimatedBytes) {
                bindOccurrence(eventFactory())
            }
        }
    }

    /**
     * Synchronously persists or publishes a critical event.
     *
     * @param event 已构造完成的 critical 事件
     * @return true when the event reached the durable local hand-off point
     */
    fun emitCriticalSync(event: ApmEvent): Boolean {
        val occurrenceBoundEvent = bindOccurrence(event)
        return if (processCoordinator != null && !isUploaderProcess) {
            selfMonitor?.recordEmit()
            val result = processCoordinator.writeEventSyncWithResult(occurrenceBoundEvent)
            if (!result.success) {
                selfMonitor?.recordDrop(
                    occurrenceBoundEvent.priority,
                    result.dropReason ?: SdkDropReason.IPC_HANDOFF_FAILURE
                )
            }
            result.success
        } else {
            dispatcher.dispatchCriticalSync(occurrenceBoundEvent)
        }
    }

    /**
     * Freezes the init-time occurrence identity before any durable or asynchronous hand-off.
     *
     * A module may provide native frames on its event occurrence object, but it cannot replace the
     * host release/build/installation values captured by the runtime configuration.
     */
    private fun bindOccurrence(event: ApmEvent): ApmEvent {
        if (config.serializationFormat != SerializationFormat.PROTOBUF_ENVELOPE_V3) {
            return event
        }
        val configured = requireNotNull(occurrenceContext) {
            "Schema V3 runtime is missing its occurrence identity"
        }
        val moduleFrames = event.occurrence?.nativeFrames
        val snapshot = configured.copy(
            nativeFrames = moduleFrames ?: configured.nativeFrames
        )
        return event.withOccurrenceContext(snapshot)
    }

    /**
     * Cross-artifact SDK bridge for reporting whether an explicit host integration monitor is running.
     *
     * This synthetic method is intentionally hidden from Java host call sites; feature artifacts use it
     * because Kotlin `internal` visibility cannot cross independently published Gradle modules.
     */
    @JvmSynthetic
    fun setHostIntegrationModuleActive(point: HostIntegrationPoint, active: Boolean) {
        HostIntegrationRegistry.setModuleActive(point, active)
    }

    /** Cross-artifact SDK bridge for reconciling current host registrations without retaining host objects. */
    @JvmSynthetic
    fun setHostIntegrationActiveRegistrations(point: HostIntegrationPoint, count: Int) {
        HostIntegrationRegistry.setActiveRegistrations(point, count)
    }

    /** Cross-artifact SDK bridge for one value-free integration entry-point signal. */
    @JvmSynthetic
    fun recordHostIntegrationObservation(point: HostIntegrationPoint) {
        HostIntegrationRegistry.recordObservation(point)
    }

    /** Creates a module-specific view while sharing all runtime infrastructure. */
    internal fun withLogger(scopedLogger: ApmLogger): ApmContext {
        return ApmContext(
            application = application,
            config = config,
            processName = processName,
            logger = scopedLogger,
            dispatcher = dispatcher,
            processCoordinator = processCoordinator,
            isUploaderProcess = isUploaderProcess,
            occurrenceContext = occurrenceContext
        ).also { scopedContext -> scopedContext.selfMonitor = selfMonitor }
    }
}
