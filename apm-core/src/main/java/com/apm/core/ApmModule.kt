package com.apm.core

/**
 * APM 功能模块接口。
 * 所有专项能力（Memory、Crash、ANR 等）都必须实现此接口，
 * 通过 [Apm.register] 注册后由框架统一管理生命周期。
 */
interface ApmModule {

    /** 模块唯一标识，用于事件路由和日志追踪。例如 "memory"、"crash"。 */
    val name: String

    /**
     * 模块初始化回调。在 [Apm.init] 或 [Apm.register] 时调用。
     * 用于获取 [ApmContext] 引用，完成模块内部依赖注入。
     *
     * @param context APM 运行上下文，提供 Application、Config、Logger 等
     */
    fun onInitialize(context: ApmContext)

    /**
     * 模块启动回调。初始化完成后调用。
     * 在此注册监听器、启动线程、打开数据通道。
     */
    fun onStart()

    /**
     * 模块停止回调。[Apm.stop] 时调用。
     * 必须释放所有资源：线程、Handler、文件句柄等。
     */
    fun onStop()
}
