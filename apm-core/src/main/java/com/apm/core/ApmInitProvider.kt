package com.apm.core

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * APM 自动初始化 ContentProvider。
 *
 * 通过 AndroidManifest.xml 声明，在 Application.onCreate() 之前自动执行初始化。
 * 支持多进程：根据 [ApmConfig.processStrategy] 决定各进程是否初始化 APM。
 *
 * 使用方式（AndroidManifest.xml）：
 * ```xml
 * <provider
 *     android:name="com.apm.core.ApmInitProvider"
 *     android:authorities="${applicationId}.apm-init"
 *     android:exported="false"
 *     android:initOrder="100" />
 * ```
 *
 * 初始化配置通过 meta-data 注入：
 * ```xml
 * <meta-data
 *     android:name="com.apm.config_class"
 *     android:value="com.example.MyApmConfigProvider" />
 * ```
 *
 * [ApmConfigProvider] 实现类必须有一个无参构造函数。
 */
class ApmInitProvider : ContentProvider() {

    /**
     * ContentProvider 自动初始化入口。
     *
     * 执行流程：
     * 1. 读取 AndroidManifest 中 `com.apm.config_class` meta-data
     * 2. 通过反射创建 [ApmConfigProvider] 实例
     * 3. 调用 [ApmConfigProvider.provideConfig] 获取配置
     * 4. 调用 [Apm.init] 完成框架初始化
     *
     * @return true 表示 ContentProvider 初始化成功
     */
    override fun onCreate(): Boolean {
        val ctx = context ?: return false

        // 从 ApplicationInfo 读取 meta-data 配置类名
        val configClassName = resolveConfigClassName(ctx)
        if (configClassName.isNullOrBlank()) {
            // 未配置 meta-data，跳过自动初始化
            Log.w(TAG, "No meta-data 'com.apm.config_class' found, skip auto-init")
            return true
        }

        // 反射创建配置提供者并初始化 APM
        try {
            val configProvider = createConfigProvider(configClassName)
            val config = configProvider.provideConfig(ctx)
            Apm.init(ctx.applicationContext as Application, config)
        } catch (e: Exception) {
            // 初始化失败不应阻塞应用启动，仅打印警告
            Log.e(TAG, "Failed to auto-init APM from provider", e)
        }

        return true
    }

    /**
     * 从 AndroidManifest.xml 的 meta-data 中读取配置类名。
     *
     * @param context 应用上下文
     * @return 配置类的完整类名，未配置时返回 null
     */
    private fun resolveConfigClassName(context: Context): String? {
        val appInfo = context.applicationInfo
        val metaData = appInfo.metaData ?: return null
        // meta-data 键名使用 APM 包名前缀，避免冲突
        return metaData.getString(META_DATA_KEY_CONFIG_CLASS)
    }

    /**
     * 通过反射创建配置提供者实例。
     * 要求配置类有无参构造函数。
     *
     * @param className 配置类的完整类名
     * @return 配置提供者实例
     * @throws Exception 反射创建失败时抛出
     */
    private fun createConfigProvider(className: String): ApmConfigProvider {
        val clazz = Class.forName(className)
        // 检查是否实现了 ApmConfigProvider 接口
        require(ApmConfigProvider::class.java.isAssignableFrom(clazz)) {
            "$className does not implement ApmConfigProvider"
        }
        // 使用无参构造函数创建实例
        return clazz.getDeclaredConstructor().newInstance() as ApmConfigProvider
    }

    // --- ContentProvider 必须实现的方法，本 Provider 不使用 ---

    /**
     * 查询操作，本 Provider 不支持。
     * @return 始终返回 null
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    /**
     * 返回 MIME 类型，本 Provider 不支持。
     * @return 始终返回 null
     */
    override fun getType(uri: Uri): String? = null

    /**
     * 插入操作，本 Provider 不支持。
     * @return 始终返回 null
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    /**
     * 删除操作，本 Provider 不支持。
     * @return 始终返回 0
     */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * 更新操作，本 Provider 不支持。
     * @return 始终返回 0
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        /** Logcat tag。 */
        private const val TAG = "ApmInitProvider"

        /** meta-data 键名：配置类全限定名。 */
        private const val META_DATA_KEY_CONFIG_CLASS = "com.apm.config_class"
    }
}

/**
 * APM 配置提供者接口。
 *
 * 宿主 App 实现此接口，在 [provideConfig] 中返回业务定制的 [ApmConfig]。
 * 实现类必须有无参构造函数。
 */
interface ApmConfigProvider {
    /**
     * 提供初始化 APM 所需的配置。
     *
     * @param context 应用上下文（Application Context）
     * @return 业务定制的 APM 配置
     */
    fun provideConfig(context: android.content.Context): ApmConfig
}
