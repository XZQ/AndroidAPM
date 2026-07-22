package com.apm.sample

import android.app.Application
import android.os.SystemClock
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
import com.apm.model.ApmEvent
import com.apm.uploader.ApmUploader

/** Application that demonstrates explicit registration of every SDK monitor. */
class SampleApplication : Application() {

    /** Whether this process initialized the SDK for a device-soak A/B phase. */
    internal var soakSdkEnabled: Boolean = true
        private set

    /** Whether this process uses a deliberately failing collector transport. */
    internal var soakOfflineCollector: Boolean = false
        private set

    /** Main-thread elapsed time spent inside [Apm.init], or zero for the control process. */
    internal var apmInitDurationNs: Long = 0L
        private set

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

    /** Thread monitor exposed so the sample can register a real host executor. */
    lateinit var threadMonitorModule: ThreadMonitorModule
        private set

    /** Battery monitor exposed for explicit WakeLock, GPS, and alarm callbacks. */
    lateinit var batteryModule: BatteryModule
        private set

    /** Initializes either the SDK-enabled sample runtime or a zero-SDK A/B control process. */
    override fun onCreate() {
        super.onCreate()

        val soakPreferences = getSharedPreferences(SOAK_PREFERENCES_NAME, MODE_PRIVATE)
        soakSdkEnabled = soakPreferences.getBoolean(SOAK_SDK_ENABLED_KEY, true)
        soakOfflineCollector = soakPreferences.getBoolean(SOAK_OFFLINE_COLLECTOR_KEY, false)

        // The control process deliberately skips every SDK allocation and module registration.
        // MainActivity recognizes soak intents before touching the lateinit demo modules below.
        if (!soakSdkEnabled) {
            return
        }

        // 1. 初始化 APM 框架
        val initStartedNs = SystemClock.elapsedRealtimeNanos()
        Apm.init(
            application = this,
            config = ApmConfig(
                endpoint = if (soakOfflineCollector) "" else "logcat://sample",
                uploader = if (soakOfflineCollector) OfflineSoakUploader else null,
                debugLogging = !soakOfflineCollector,
                maxRetries = if (soakOfflineCollector) SOAK_MAX_RETRIES else DEFAULT_MAX_RETRIES,
                retryBaseDelayMs = if (soakOfflineCollector) {
                    SOAK_RETRY_DELAY_MS
                } else {
                    DEFAULT_RETRY_DELAY_MS
                },
                defaultContext = mapOf(
                    "appId" to packageName,
                    "buildType" to if (BuildConfig.DEBUG) "debug" else "release"
                ),
                bizContextProvider = BizContextProvider {
                    mapOf("session" to "sample-demo")
                }
            )
        )
        apmInitDurationNs = (SystemClock.elapsedRealtimeNanos() - initStartedNs).coerceAtLeast(0L)

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
        ioModule = IoModule(IoConfig(singleIoThresholdMs = 0L))
        Apm.register(ioModule)

        // 10. 注册线程监控模块
        threadMonitorModule = ThreadMonitorModule(
            ThreadMonitorConfig(checkIntervalMs = 1_000L, queueBacklogThreshold = 3)
        )
        Apm.register(threadMonitorModule)

        // 11. 注册电量监控模块
        batteryModule = BatteryModule(
            BatteryConfig(
                wakeLockThresholdMs = 500L,
                gpsThresholdMs = 500L,
                checkIntervalMs = 1_000L,
                alarmFloodThreshold = 3
            )
        )
        Apm.register(batteryModule)

        // 12. 注册 SQLite 监控模块
        sqliteModule = SqliteModule(
            SqliteConfig(slowQueryThresholdMs = 0L, queryPlanThresholdMs = 0L)
        )
        Apm.register(sqliteModule)

        // 13. 注册 WebView 监控模块
        webviewModule = WebviewModule(
            WebviewConfig(pageLoadThresholdMs = 0L, jsExecutionThresholdMs = 0L)
        )
        Apm.register(webviewModule)

        // 14. 注册 IPC 监控模块
        ipcModule = IpcModule(
            IpcConfig(binderThresholdMs = 20L, mainThreadBinderThresholdMs = 20L)
        )
        Apm.register(ipcModule)

        // 15. 注册 GC 监控模块
        Apm.register(GcMonitorModule(GcMonitorConfig()))

        // 16. 注册渲染监控模块
        Apm.register(RenderModule(RenderConfig()))
    }

    /** Failing transport used to exercise the durable outbox without changing device networking. */
    private object OfflineSoakUploader : ApmUploader {
        /** Keeps every accepted event in the durable retry path. */
        override fun upload(event: ApmEvent): Boolean = false
    }

    companion object {
        /** App-private preferences edited by the exported sample Activity for the next process. */
        internal const val SOAK_PREFERENCES_NAME = "apm-device-soak"

        /** Boolean preference selecting the SDK-enabled or control process. */
        internal const val SOAK_SDK_ENABLED_KEY = "sdk-enabled"

        /** Boolean preference selecting a failing collector transport. */
        internal const val SOAK_OFFLINE_COLLECTOR_KEY = "offline-collector"

        /** Retries retained long enough for a 72-hour offline campaign. */
        private const val SOAK_MAX_RETRIES = 10_000

        /** One-minute retry cadence limits transport churn during an offline campaign. */
        private const val SOAK_RETRY_DELAY_MS = 60_000L

        /** Interactive sample default matching [ApmConfig]. */
        private const val DEFAULT_MAX_RETRIES = 3

        /** Interactive sample retry delay matching [ApmConfig]. */
        private const val DEFAULT_RETRY_DELAY_MS = 1_000L
    }
}
