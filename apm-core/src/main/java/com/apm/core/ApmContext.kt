package com.apm.core

import android.app.Application
import com.apm.model.ApmEvent

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
    private val dispatcher: ApmDispatcher
) {
    /**
     * 发送事件到 APM 分发通道。
     * 事件会经过限流检查 → 本地存储 → 上传。
     *
     * @param event 已构造完成的 APM 事件
     */
    fun emit(event: ApmEvent) {
        dispatcher.dispatch(event)
    }
}
