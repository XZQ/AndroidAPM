package com.didi.apm.sample

import android.app.Application
import com.didi.apm.anr.AnrConfig
import com.didi.apm.anr.AnrModule
import com.didi.apm.battery.BatteryConfig
import com.didi.apm.battery.BatteryModule
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmConfig
import com.didi.apm.core.BizContextProvider
import com.didi.apm.crash.CrashConfig
import com.didi.apm.crash.CrashModule
import com.didi.apm.fps.FpsConfig
import com.didi.apm.fps.FpsModule
import com.didi.apm.gcmonitor.GcMonitorConfig
import com.didi.apm.gcmonitor.GcMonitorModule
import com.didi.apm.io.IoConfig
import com.didi.apm.io.IoModule
import com.didi.apm.ipc.IpcConfig
import com.didi.apm.ipc.IpcModule
import com.didi.apm.launch.LaunchConfig
import com.didi.apm.launch.LaunchModule
import com.didi.apm.memory.MemoryConfig
import com.didi.apm.memory.MemoryModule
import com.didi.apm.network.NetworkConfig
import com.didi.apm.network.NetworkModule
import com.didi.apm.render.RenderConfig
import com.didi.apm.render.RenderModule
import com.didi.apm.slowmethod.SlowMethodConfig
import com.didi.apm.slowmethod.SlowMethodModule
import com.didi.apm.sqlite.SqliteConfig
import com.didi.apm.sqlite.SqliteModule
import com.didi.apm.threadmonitor.ThreadMonitorConfig
import com.didi.apm.threadmonitor.ThreadMonitorModule
import com.didi.apm.webview.WebviewConfig
import com.didi.apm.webview.WebviewModule

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
