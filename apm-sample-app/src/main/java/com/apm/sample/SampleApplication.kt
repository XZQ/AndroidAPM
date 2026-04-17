package com.apm.sample

import android.app.Application
import com.apm.anr.AnrConfig
import com.apm.anr.AnrModule
import com.apm.battery.BatteryConfig
import com.apm.battery.BatteryModule
import com.apm.core.Apm
import com.apm.core.ApmConfig
import com.apm.core.BizContextProvider
import com.apm.crash.CrashConfig
import com.apm.crash.CrashModule
import com.apm.fps.FpsConfig
import com.apm.fps.FpsModule
import com.apm.gcmonitor.GcMonitorConfig
import com.apm.gcmonitor.GcMonitorModule
import com.apm.io.IoConfig
import com.apm.io.IoModule
import com.apm.ipc.IpcConfig
import com.apm.ipc.IpcModule
import com.apm.launch.LaunchConfig
import com.apm.launch.LaunchModule
import com.apm.memory.MemoryConfig
import com.apm.memory.MemoryModule
import com.apm.network.NetworkConfig
import com.apm.network.NetworkModule
import com.apm.render.RenderConfig
import com.apm.render.RenderModule
import com.apm.slowmethod.SlowMethodConfig
import com.apm.slowmethod.SlowMethodModule
import com.apm.sqlite.SqliteConfig
import com.apm.sqlite.SqliteModule
import com.apm.threadmonitor.ThreadMonitorConfig
import com.apm.threadmonitor.ThreadMonitorModule
import com.apm.webview.WebviewConfig
import com.apm.webview.WebviewModule

class SampleApplication : Application() {

    /** 内存模块引用，供 MainActivity 调用 captureOnce。 */
    lateinit var memoryModule: MemoryModule
        private set

    /** 网络模块引用，供 MainActivity 模拟网络请求。 */
    lateinit var networkModule: NetworkModule
        private set

    /** IO 模块引用，供 MainActivity 模拟 IO 操作。 */
    lateinit var ioModule: IoModule
        private set

    /** SQLite 模块引用，供 MainActivity 模拟慢查询。 */
    lateinit var sqliteModule: SqliteModule
        private set

    /** WebView 模块引用，供 MainActivity 模拟页面加载。 */
    lateinit var webviewModule: WebviewModule
        private set

    /** IPC 模块引用，供 MainActivity 模拟 Binder 调用。 */
    lateinit var ipcModule: IpcModule
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化 APM 框架
        Apm.init(
            application = this,
            config = ApmConfig(
                endpoint = "logcat://sample",
                debugLogging = true,
                defaultContext = mapOf(
                    "appId" to packageName,
                    "buildType" to if (BuildConfig.DEBUG) "debug" else "release"
                ),
                bizContextProvider = BizContextProvider {
                    mapOf("session" to "sample-demo")
                }
            )
        )

        // 2. 注册 Memory 模块
        memoryModule = MemoryModule(
            MemoryConfig(
                foregroundIntervalMs = 5_000L,
                backgroundIntervalMs = 20_000L,
                javaHeapWarnRatio = 0.70f,
                javaHeapCriticalRatio = 0.85f,
                totalPssWarnKb = 180 * 1024,
                enableActivityLeak = true,
                enableFragmentLeak = true,
                enableViewModelLeak = true,
                enableOomMonitor = true,
                enableHprofDump = true,
                enableHprofStrip = true,
                enableNativeMonitor = true
            )
        )
        Apm.register(memoryModule)

        // 3. 注册 Crash 模块
        Apm.register(CrashModule(CrashConfig(enableJavaCrash = true)))

        // 4. 注册 ANR 模块
        Apm.register(AnrModule(AnrConfig(checkIntervalMs = 3000L, anrTimeoutMs = 3000L)))

        // 5. 注册 Launch 模块
        Apm.register(LaunchModule(LaunchConfig()))

        // 6. 注册 Network 模块
        networkModule = NetworkModule(NetworkConfig(slowThresholdMs = 2000L))
        Apm.register(networkModule)

        // 7. 注册 FPS 模块
        Apm.register(FpsModule(FpsConfig()))

        // 8. 注册慢方法检测模块
        Apm.register(SlowMethodModule(SlowMethodConfig()))

        // 9. 注册 IO 监控模块
        ioModule = IoModule(IoConfig())
        Apm.register(ioModule)

        // 10. 注册线程监控模块
        Apm.register(ThreadMonitorModule(ThreadMonitorConfig()))

        // 11. 注册电量监控模块
        Apm.register(BatteryModule(BatteryConfig()))

        // 12. 注册 SQLite 监控模块
        sqliteModule = SqliteModule(SqliteConfig())
        Apm.register(sqliteModule)

        // 13. 注册 WebView 监控模块
        webviewModule = WebviewModule(WebviewConfig())
        Apm.register(webviewModule)

        // 14. 注册 IPC 监控模块
        ipcModule = IpcModule(IpcConfig())
        Apm.register(ipcModule)

        // 15. 注册 GC 监控模块
        Apm.register(GcMonitorModule(GcMonitorConfig()))

        // 16. 注册渲染监控模块
        Apm.register(RenderModule(RenderConfig()))
    }
}
