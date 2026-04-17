package com.apm.core

/**
 * 多进程模块过滤器。
 *
 * 根据配置的进程策略，决定每个模块在当前进程是否应该启用。
 * 支持三种模式：
 * - [ProcessStrategy.MAIN_PROCESS_ONLY] 仅主进程运行所有模块
 * - [ProcessStrategy.ALL_PROCESSES] 所有进程都运行所有模块
 * - [ProcessStrategy.CUSTOM] 自定义进程映射，通过 customProcessModules 指定每个进程运行的模块
 *
 * 使用方式：
 * ```kotlin
 * val filter = ProcessModuleFilter
 * val shouldRun = filter.shouldRunInCurrentProcess(
 *     moduleName = "memory",
 *     processName = currentProcess,
 *     strategy = config.processStrategy,
 *     customMapping = config.customProcessModules
 * )
 * if (!shouldRun) return
 * ```
 */
object ProcessModuleFilter {

    /**
     * 检查模块是否应该在当前进程中运行。
     *
     * 决策逻辑：
     * - MAIN_PROCESS_ONLY：仅当进程名为包名（主进程）时返回 true
     * - ALL_PROCESSES：始终返回 true
     * - CUSTOM：查找当前进程在映射表中对应的模块列表，判断模块名是否存在
     *
     * @param moduleName 模块标识名，如 "memory"、"crash"
     * @param processName 当前进程名
     * @param strategy 进程策略
     * @param customMapping 自定义进程模块映射（进程名 → 模块名列表），仅 CUSTOM 模式使用
     * @return true 表示模块应该在该进程运行，false 表示跳过
     */
    fun shouldRunInCurrentProcess(
        moduleName: String,
        processName: String,
        strategy: ProcessStrategy,
        customMapping: Map<String, List<String>> = emptyMap()
    ): Boolean {
        return when (strategy) {
            // 所有进程都运行所有模块
            ProcessStrategy.ALL_PROCESSES -> true

            // 仅主进程运行；主进程名不含冒号（子进程格式为 "包名:xxx"）
            ProcessStrategy.MAIN_PROCESS_ONLY -> isMainProcess(processName)

            // 自定义映射：查找进程对应的模块列表
            ProcessStrategy.CUSTOM -> {
                val allowedModules = customMapping[processName]
                // 进程不在映射表中时默认不运行任何模块
                if (allowedModules == null) {
                    // 回退：如果进程名对应主进程，仍然允许所有模块
                    isMainProcess(processName)
                } else {
                    // 模块名在允许列表中
                    moduleName in allowedModules
                }
            }
        }
    }

    /**
     * 判断进程名是否为主进程。
     *
     * 主进程名等于包名（不含冒号分隔符）。
     * 子进程名格式为 "包名:进程后缀"，例如 "com.example:push"。
     *
     * @param processName 进程名
     * @return true 表示主进程
     */
    private fun isMainProcess(processName: String): Boolean {
        // 子进程名包含冒号分隔符（如 ":remote"），主进程不包含
        return !processName.contains(PROCESS_SEPARATOR)
    }

    /**
     * 获取当前进程中允许运行的所有模块名。
     *
     * @param processName 当前进程名
     * @param allModules 所有已注册的模块名列表
     * @param strategy 进程策略
     * @param customMapping 自定义进程模块映射
     * @return 当前进程允许运行的模块名列表
     */
    fun filterModulesForProcess(
        processName: String,
        allModules: List<String>,
        strategy: ProcessStrategy,
        customMapping: Map<String, List<String>> = emptyMap()
    ): List<String> {
        return when (strategy) {
            // 所有进程都运行所有模块
            ProcessStrategy.ALL_PROCESSES -> allModules

            // 仅主进程运行所有模块
            ProcessStrategy.MAIN_PROCESS_ONLY -> {
                if (isMainProcess(processName)) allModules else emptyList()
            }

            // 自定义映射：返回允许列表与已注册模块的交集
            ProcessStrategy.CUSTOM -> {
                val allowed = customMapping[processName]
                if (allowed == null) {
                    // 未配置的进程默认不运行模块，除非是主进程
                    if (isMainProcess(processName)) allModules else emptyList()
                } else {
                    // 只保留同时在允许列表和已注册列表中的模块
                    allModules.filter { it in allowed }
                }
            }
        }
    }

    /** 子进程名中分隔主包名和后缀的冒号。 */
    private const val PROCESS_SEPARATOR = ":"
}
