package com.apm.model

/**
 * 事件优先级枚举。
 * 用于上传队列排序和降级策略：优先级越高的事件越先上传，
 * 队列满时低优先级事件优先丢弃。
 */
enum class ApmPriority(val value: Int) {
    /** 低优先级：电池、GC、线程、渲染、WebView 等辅助监控。 */
    LOW(0),
    /** 普通优先级：FPS、慢方法、IO、网络、IPC、SQLite 等常规监控。 */
    NORMAL(1),
    /** 高优先级：OOM 预警、泄漏检测、启动瓶颈等需要快速到达的事件。 */
    HIGH(2),
    /** 关键优先级：Java 崩溃、Native 崩溃、ANR 等必须立即上传的事件。 */
    CRITICAL(3)
}
