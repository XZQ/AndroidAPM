package com.apm.anr

/**
 * SIGQUIT 信号标志源。
 *
 * Native 层信号处理器只记录时间戳标志（异步信号安全），
 * Java 侧通过该接口在 watchdog 循环中轮询消费。
 * 抽象为接口以便 JVM 单元测试注入替身，无需加载 JNI 库。
 */
internal fun interface SigquitFlagSource {

    /**
     * 消费并清除最近一次 SIGQUIT 的时间戳。
     *
     * @return epoch 毫秒时间戳；0 表示自上次消费以来没有新信号
     */
    fun consumeSigquitTimestampMs(): Long
}

/**
 * SIGQUIT 标志轮询器。
 * 从 watchdog 循环中抽出的纯逻辑：消费标志源，有信号时触发分析回调。
 * 独立成类便于 JVM 单元测试覆盖。
 */
internal class SigquitFlagPoller(
    /** 信号标志来源（native 或测试替身）。 */
    private val source: SigquitFlagSource,
    /** 检测到信号时的回调（转发到 SIGQUIT 分析调度）。 */
    private val onSigquit: () -> Unit
) {

    /**
     * 执行一次轮询。
     *
     * @return true 表示本轮消费到了 SIGQUIT 信号并已触发回调
     */
    fun pollOnce(): Boolean {
        // 原子消费信号时间戳；0 表示无新信号
        val timestampMs = source.consumeSigquitTimestampMs()
        if (timestampMs <= 0L) {
            return false
        }
        // 系统已投递 SIGQUIT（通常意味着系统判定 ANR），触发分析
        onSigquit()
        return true
    }
}
