package com.apm.io

/**
 * Native IO Hook 安装边界。
 *
 * 将 JNI 库加载和 Hook 安装失败处理从 [NativeIoHook] 中拆出，便于验证
 * "Native 不可用时降级到 Java 代理" 的稳定行为。
 *
 * @param loadLibrary Native 库加载动作。
 * @param installHooks Native Hook 安装动作。
 * @param uninstallHooks Native Hook 卸载动作。
 */
internal class NativeIoHookInstaller(
    private val loadLibrary: () -> Unit,
    private val installHooks: () -> Unit,
    private val uninstallHooks: () -> Unit
) {
    /** Native Hook 是否已成功安装。 */
    private var installed = false

    /**
     * 尝试安装 Native Hook。
     *
     * @return true 表示安装成功；false 表示应降级 Java 代理。
     */
    fun install(): Boolean {
        return try {
            loadLibrary()
            installHooks()
            installed = true
            true
        } catch (_: UnsatisfiedLinkError) {
            // JNI 库缺失是预期降级路径。
            installed = false
            false
        } catch (_: Exception) {
            // Native Hook 安装异常同样降级，避免影响宿主启动。
            installed = false
            false
        }
    }

    /**
     * 卸载已安装的 Native Hook。
     *
     * @return true 表示执行过卸载；false 表示此前未安装或卸载失败。
     */
    fun uninstall(): Boolean {
        if (!installed) return false
        return try {
            uninstallHooks()
            installed = false
            true
        } catch (_: Exception) {
            // 卸载失败后仍标记为未安装，避免 destroy 重复调用。
            installed = false
            false
        }
    }
}
