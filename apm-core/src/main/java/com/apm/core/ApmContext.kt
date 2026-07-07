package com.apm.core

import android.app.Application
import com.apm.model.ApmEvent
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
    private val isUploaderProcess: Boolean = true
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
        if (processCoordinator != null && !isUploaderProcess) {
            processCoordinator.writeEvent(event)
        } else {
            dispatcher.dispatch(event)
        }
    }

    /**
     * 发送延迟构建的事件到 APM 分发通道。
     * 主进程路径把事件构建推迟到 dispatcher worker 线程，降低发射线程开销；
     * 子进程 IPC 路径需要完整事件内容，立即构建。
     *
     * @param eventFactory 事件构建工厂（纯函数，可在任意线程执行）
     */
    internal fun emitLazy(eventFactory: () -> ApmEvent) {
        if (processCoordinator != null && !isUploaderProcess) {
            // IPC 写文件需要完整事件，立即构建
            processCoordinator.writeEvent(eventFactory())
        } else {
            dispatcher.dispatchLazy(eventFactory)
        }
    }

    /**
     * Synchronously persists or publishes a critical event.
     *
     * @param event 已构造完成的 critical 事件
     * @return true when the event reached the durable local hand-off point
     */
    fun emitCriticalSync(event: ApmEvent): Boolean {
        return if (processCoordinator != null && !isUploaderProcess) {
            processCoordinator.writeEventSync(event)
        } else {
            dispatcher.dispatchCriticalSync(event)
        }
    }
}
