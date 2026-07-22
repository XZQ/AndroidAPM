# Android APM Framework

模块化 Android 应用性能监控客户端 SDK。项目提供 15 个监控模块、稳定事件身份、SQLite 持久化出箱、并发上传租约、动态短期鉴权、Ed25519 签名远程配置、批量 HTTP 上传、独立 SDK 自诊断日志、手动 Trace API、OpenTelemetry 语义映射和真机 benchmark harness。

> 当前边界：本仓库负责 Android 端采集、保护、持久化和传输，不包含生产 Collector、查询/告警后台、Native 符号化服务或托管平台。

## 当前基线

- 同步日期：2026-07-22
- 26 个构建单元：24 个 root subproject + `apm-plugin`、`build-logic` 两个 included build
- 156 个主源码文件：151 Kotlin + 4 C + 1 proto
- 93 个测试/benchmark 文件
- Kotlin 2.2.21 / AGP 8.13.2 / Gradle 8.13 / Java 17 toolchain（Gradle runtime JDK 17+）
- compileSdk 34 / minSdk 24 / targetSdk 34 / Java 17 字节码

详细状态见 [项目文档](docs/Android_APM_项目文档.md)，换机接手见 [交接快照](docs/PROJECT_HANDOFF.md)，模块设计见 [架构文档](docs/architecture/README.md)。所有云端事项统一由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 维护。

## 第一性原理架构

APM 客户端必须同时满足三件事：采集结果可信、监控开销受控、失败数据可恢复。因此运行时主链路是：

```text
监控模块
  -> Apm.emit（调用线程捕获时间/线程/业务上下文及 payload 快照）
  -> 有界队列 2048（高优事件可替换最低优先级事件，生产者永不等待）
  -> 可选聚合 -> 限流 -> 默认 PII 脱敏
  -> appendBatch（单轮最多 32 条）
  -> SQLite durable outbox v3（50,000 行 / 64 MiB 活跃 payload，单事件软上限 256 KiB，eventId 唯一）
  -> claim(owner, lease, expiry) -> PersistentUploadWorker
  -> BatchApmUploader / HttpApmUploader / 自定义 uploader
  -> 接入方 Collector
```

Crash 等关键事件可同步落盘，但不会在崩溃线程执行阻塞网络请求。非上传进程可选择通过 `.tmp` 写入、`.ipc` 发布的文件通道交给主进程。

每个事件创建时获得稳定 `eventId`，Line Protocol、Protobuf、durable codec、SQLite 和多进程文件交接全程保留。上传 Worker 先原子 claim，只有当前 owner 能 ACK/失败释放；租约过期后其他进程或 Worker 可安全重领。上传成功后才删除，失败保留并指数退避；`maxRetries` 表示首次尝试后的重试次数，达到 `maxRetries + 1` 次失败后立即清理，超过 7 天的行也会清理。这仍是至少一次语义：网络响应丢失时可能重传，服务端必须按 `eventId` 幂等去重。

生产可靠性优先级固定为“宿主安全 > telemetry durability > diagnostic completeness”。单个 lazy event/聚合/脱敏异常不会杀死共享 dispatcher worker；recoverable `Exception` 会降级并记录，`OutOfMemoryError` 等 fatal VM error 不会被伪装成普通丢包或重试。SQLite 编码会隔离单个超限/非法 payload，使同批正常事件继续落盘；行数或活跃 payload 预算淘汰会进入 SDK drop 计数。Retry-After 与本地退避合并后限制为 10 ms–60 s；自定义同步 uploader 必须自行保证网络调用有界，SDK 无法安全终止任意宿主代码，进程恢复仍以 claim expiry 为准。

## 模块组成

### 5 个基础模块

| 模块 | 职责 |
|---|---|
| `apm-model` | `ApmEvent`、priority/severity、Line Protocol、Protobuf、持久化 codec |
| `apm-core` | 初始化、模块生命周期、分发、限流、聚合、脱敏、多进程、自监控、独立本地诊断日志 |
| `apm-storage` | SQLite durable outbox；FileEventStore 兼容路径 |
| `apm-uploader` | HTTP/Logcat/自定义上传、逐请求动态 Header/endpoint、批量、Gzip、Retry-After、内存重试兼容路径 |
| `apm-remote-config` | HTTPS 拉取、ETag、Ed25519/Tink 验签、回滚/同 revision 篡改保护、可信过期和 app-private LKG 缓存 |

### 15 个监控模块

| 模块 | 已实现能力 | 接入方式 |
|---|---|---|
| `apm-memory` | Heap/PSS/Native Heap、泄漏、OOM 预警、Hprof dump/裁剪 | 注册即采样；ViewModel 检查需调用 API；高风险 dump 默认关闭 |
| `apm-crash` | Java crash、可选 Native signal、tombstone、ApplicationExitInfo | Java 默认开启；Native crash 默认关闭 |
| `apm-anr` | `libapm-anr.so` SIGQUIT 标志 + Watchdog、堆栈采样、原因分类 | 注册后自动运行，Native 失败自动降级 Watchdog |
| `apm-launch` | 进程真实启动基线、冷/热/温启动、首帧、阶段跟踪 | Activity 生命周期自动；ContentProvider/App 阶段需宿主调用 |
| `apm-network` | OkHttp DNS→TCP→TLS→Body、慢请求和聚合 | 接入 Interceptor/EventListener，或手动回调 |
| `apm-fps` | Choreographer 一秒时间窗口 + FrameMetrics 原始类型滚动累计、掉帧分级 | Activity 生命周期自动 |
| `apm-slow-method` | Looper Hook、栈采样、ASM 方法插桩 | 运行时注册；ASM 需应用 `com.apm.slow-method` 插件 |
| `apm-io` | 流代理、主线程/慢 IO、FD/Closeable 泄漏、可选 PLT Hook | 包装流；Native 路径依赖运行时可解析 xhook |
| `apm-battery` | 电量下降、CPU Jiffies、WakeLock/GPS/Alarm 统计 | 电量/CPU 自动；其余需宿主转发生命周期 |
| `apm-sqlite` | 慢 SQL、主线程 DB、大影响行数、QueryPlan | 使用 `ApmSQLiteDatabase` 或手动回调 |
| `apm-webview` | 页面、JS、白屏、Bridge、Console、资源瀑布 | 对指定 WebView `install/uninstall`，或使用 delegate wrapper/手动回调 |
| `apm-ipc` | Binder 调用耗时、主线程阈值、固定窗口聚合 | `traceBinderCall` 或 `onBinderCallComplete`；不使用隐藏 API |
| `apm-thread-monitor` | 线程数、同名线程、BLOCKED、线程池 backlog | 定时采样；线程池需显式注册真实 `ThreadPoolExecutor` |
| `apm-gc-monitor` | GC 次数/耗时、Heap 增长、分配率、回收率 | 定时读取运行时统计 |
| `apm-render` | View 数量/层级 + API 24 `FrameMetrics` 帧耗时窗口 | Activity 生命周期自动；公共 API 不支持 GPU overdraw 计数 |

### 扩展与构建工具

| 模块 | 作用 |
|---|---|
| `apm-trace` | 手动 Span/Trace API，Span 结束后进入统一事件管线 |
| `apm-otel-exporter` | 把事件映射为 OTel-compatible Map；不依赖或发送到 OTel SDK |
| `apm-plugin` | AGP instrumentation + ASM，仅插桩宿主 project class |
| `build-logic` | 统一 Android library 的 compileSdk/minSdk/Java 配置 |
| `apm-sample-app` | 15 个监控模块的本地演示；包含 IO/SQLite/WebView/IPC/线程池/Battery 显式接线，并显式配置 `logcat://sample` 输出 |
| `apm-benchmark` | 非发布 AndroidX Microbenchmark；event codec 与 SQLite outbox 真机开销 harness |

## 快速接入

### 添加依赖

本地开发：

```kotlin
dependencies {
    implementation(project(":apm-memory"))
    implementation(project(":apm-network"))
    implementation(project(":apm-remote-config"))
}
```

发布到制品库后：

```kotlin
dependencies {
    implementation("com.apm:apm-memory:0.1.0")
    implementation("com.apm:apm-network:0.1.0")
    implementation("com.apm:apm-remote-config:0.1.0")
}
```

当前仓库只验证过 `publishToMavenLocal` 和独立 Maven consumer；尚未发布 Maven Central 或外部私有制品库。

### 选择一种初始化方式

手动初始化适合需要在 `Application` 中明确控制配置和注册顺序的宿主，也是 sample 使用的方式。由于 `apm-core` 的 manifest 自带可选自动初始化 Provider，手动模式应在宿主 manifest 中显式移除它：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <provider
            android:name="com.apm.core.ApmInitProvider"
            tools:node="remove" />
    </application>
</manifest>
```

自动初始化模式不调用 `Apm.init`，而是在 manifest 提供一个有无参构造函数的 `ApmConfigProvider`：

```xml
<meta-data
    android:name="com.apm.config_class"
    android:value="com.example.MyApmConfigProvider" />
```

```kotlin
class MyApmConfigProvider : ApmConfigProvider {
    override fun provideConfig(context: Context): ApmConfig =
        ApmConfig(endpoint = "https://collector.example.com/v1/events")
}
```

没有该 metadata 时 Provider 只做 no-op，不会把手动模式记为告警。两种模式不要同时使用。

### 手动初始化并注册

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        Apm.init(
            this,
            ApmConfig(
                endpoint = "https://collector.example.com/v1/events",
                enableHttpGzip = true,
                enablePiiSanitization = true,
                defaultContext = mapOf("appId" to packageName)
            )
        )

        Apm.register(CrashModule())
        Apm.register(AnrModule())
        Apm.register(MemoryModule())
        Apm.register(LaunchModule())
    }
}
```

### 生产鉴权与签名远程配置

长期密钥不要写进 APK。`HttpHeaderProvider` 在每次上传和配置拉取前重新读取短期 Token；provider 失败或 Header 非法时上传返回失败，SQLite outbox 保留事件等待后续重试。Collector 协议身份使用静态非密钥 Header：

```kotlin
val authHeaders = HttpHeaderProvider {
    tokenStore.currentAccessToken()?.let { token ->
        mapOf("Authorization" to "Bearer $token")
    } ?: emptyMap()
}

val remoteConfig = SignedRemoteConfigProvider(
    context = this,
    config = RemoteConfigClientConfig(
        endpoint = "https://collector.example.com/v1/config",
        appId = packageName,
        environment = "production",
        installationId = installationIds.anonymousStableId()
    ),
    publicKeysBase64 = mapOf(
        "key-2026" to BuildConfig.APM_CONFIG_ED25519_PUBLIC_KEY
    ),
    headerProvider = authHeaders
)

Apm.init(
    this,
    ApmConfig(
        endpoint = "https://collector.example.com/v1/events",
        dynamicConfigProvider = remoteConfig,
        httpHeaders = mapOf(
            "X-APM-Schema-Version" to "1",
            "X-APM-App-Id" to packageName,
            "X-APM-Environment" to "production",
            "X-APM-SDK-Version" to "0.1.0"
        ),
        httpHeaderProvider = authHeaders,
        enableDynamicHttpEndpoint = true
    )
)
```

公钥值是标准 Base64 编码的 32 字节原始 Ed25519 公钥，应通过受审计的构建配置固定，不能随远程响应下发。配置客户端默认 15 分钟轮询，支持 ETag/304、256 KiB 响应上限、服务端时间锚点、过期回退、最高 revision 持久化和 204 主动停用；生产只接受 HTTPS。

运行时自动消费以下签名键：

| 键 | 作用 |
|---|---|
| `apm.enabled` | 全局紧急 kill switch，动态停止/恢复已注册模块 |
| `apm.module.<module>.enabled` | 单模块动态停止/恢复 |
| `apm.sampling.default_basis_points` | 默认事件采样率，0–10000 |
| `apm.sampling.<module>[.<event>].basis_points` | 模块/事件级采样覆盖 |
| `apm.rate_limit.default_events_per_window` / `default_window_ms` | 默认动态限流 |
| `apm.rate_limit.<module>[.<event>].events_per_window` / `window_ms` | 模块/事件级限流覆盖 |
| `apm.upload.endpoint` | 上传地址轮换；仅在 `enableDynamicHttpEndpoint=true` 时接受无凭据 HTTPS URL |

ERROR/FATAL 事件绕过采样和限流。服务端 rollout 先按匿名 `installationId` 稳定分流，客户端再使用上述精细策略；配置缺失、过期、验签失败、降 revision、同 revision 不同签名或持久化失败时都不会发布未可信值。

### 网络监控

```kotlin
val networkModule = NetworkModule()
Apm.register(networkModule)

val client = OkHttpClient.Builder()
    .addInterceptor(ApmNetworkInterceptor(networkModule))
    .eventListenerFactory(ApmEventListener.factory(networkModule))
    .build()
```

### SQLite 监控

```kotlin
val sqliteModule = SqliteModule()
Apm.register(sqliteModule)

val monitoredDb = ApmSQLiteDatabase(delegateDatabase, sqliteModule)
```

### IPC、WebView 与线程池显式接入

Android 没有受支持的进程级 Binder/WebView 全局 Hook。SDK 保留旧配置字段但已弃用且默认关闭，生产接入使用可验证的公共 API：

```kotlin
val ipc = IpcModule()
val result = ipc.traceBinderCall("com.example.IUser", "load") {
    userService.load()
}

val webview = WebviewModule()
webview.install(webView, hostWebViewClient, hostWebChromeClient)
webview.evaluateJavascript(webView, "window.performance.timing") { result ->
    // 原宿主 callback 保持不变
}

val threads = ThreadMonitorModule()
threads.registerThreadPool("image-loader", imageExecutor)
```

`WebviewModule.uninstall` 会恢复原 delegate；Render 在 Activity 可见期使用 API 24+ `FrameMetrics`。GPU overdraw 没有稳定公共计数 API，因此 `detectOverdraw` 仅为弃用兼容字段并默认 `false`。

IO 的 Java 层同样采用显式 `wrapInputStream` / `wrapOutputStream`；旧 `enableAutoHook` 已弃用并默认 `false`，不会宣称接管任意流。`throughputWindow` 每累计指定操作数输出一次 `io_throughput`；Java wrapper 调用用 ThreadLocal 标记并抑制其同线程同步 Native 回调，既不双计常规文件流，也不漏掉内存/自定义流。若自定义流把底层 syscall 转交给另一线程，ThreadLocal 无法跨线程关联，宿主应只启用 wrapper 或 Native 路径之一。Native 路径对非 ASCII/非法路径字节使用 `%HH`，并给截断路径追加哈希后缀。duplicate-read 和 small-buffer 每个路径只在首次达到阈值/条件时上报。使用 `ApmSQLiteDatabase.rawQuery` 且查询达到阈值时，会在原始数据库上对完整 SQL 与绑定参数执行 `EXPLAIN QUERY PLAN` 并输出 `query_plan_issue`；手动 `onSqlExecuted` 没有数据库句柄，因此只做耗时/线程/影响行数判断。GC 分配率与回收率来自后台线程上的相邻 ART 累计字节计数窗口，使用单调时钟；任一累计计数缺失或重置的窗口会跳过对应派生维度并保留可计算的 GC/Heap 检测。

### 慢方法 ASM 插桩

```kotlin
plugins {
    id("com.apm.slow-method")
}

apmSlowMethod {
    enabled = true
    excludePackages = listOf("android.", "androidx.", "com.apm.slowmethod.")
}
```

## 重要默认值

| 配置 | 默认值 | 含义 |
|---|---:|---|
| `endpoint` | 空 | 安全丢弃且不输出 payload；开发调试须显式使用 `logcat://...` |
| `storageType` | `SQLITE` | 使用 durable outbox |
| `maxEventPayloadBytes` | `262144` | 单事件 durable payload 软上限；超限事件单独拒绝 |
| `maxStoredPayloadBytes` | `67108864` | SQLite 活跃 payload 逻辑预算；不含 page/WAL 开销 |
| `enableAggregation` | `false` | 不聚合客户端指标 |
| `enablePiiSanitization` | `true` | 默认按文本规则和高置信敏感字段名脱敏；关闭前必须完成隐私评审 |
| `debugLogging` | `false` | 默认不输出 SDK 调试日志 |
| `enableMultiProcessCoordination` | `false` | 不转发子进程事件 |
| `enableSelfMonitoring` | `true` | 周期上报 SDK 健康事件 |
| `enableAutoThrottle` | `true` | 健康恶化时立即停模块；连续 3 个健康周期后按配置门禁恢复 |
| `enableDynamicHttpEndpoint` | `false` | 不允许远程配置改变上传目的地 |
| `httpHeaders` | 空 | 仅放协议身份等非密钥静态 Header |
| `httpHeaderProvider` | 空 | 每次请求动态获取短期凭据 |
| `diagnostics.enabled` | `true` | 独立本地诊断日志，不依赖事件上传管线 |
| Native Crash | `false` | 避免默认启用高风险信号能力 |
| Hprof/fork dump | `false` | 避免默认产生大文件或依赖设备兼容性 |
| `uploadLeaseDurationMs` | `120000` | durable batch owner 租约；超时后可被其他 Worker 重领 |
| `IoConfig.enableAutoHook` | `false` / deprecated | 无公共全局 Java IO Hook；使用显式流 wrapper |

## 与微信 Matrix、快手 KOOM 的定位对比

比较日期：2026-07-16。依据当前仓库代码，以及 [Tencent/Matrix 官方 README](https://github.com/Tencent/matrix) 与 [KwaiAppTeam/KOOM 官方 README](https://github.com/KwaiAppTeam/KOOM)。`✅` 表示该项目官方资料中有对应客户端能力，`◐` 表示范围较窄、依赖显式接入或只覆盖其中一部分，`—` 表示官方资料未声明；这不是性能排名。

| 客户端能力 | AndroidAPM | 微信 Matrix | 快手 KOOM |
|---|:---:|:---:|:---:|
| 模块化综合 APM（多个性能域） | ✅ | ✅ | ◐（聚焦 OOM/内存） |
| Activity/Fragment/ViewModel Java 泄漏 | ✅ | ✅ | ✅（Java Heap） |
| Native Heap 泄漏定位 | ◐（统计/预警，非全堆泄漏追踪） | ✅（Memory Hook） | ✅ |
| Java/Native Crash 与历史退出原因 | ✅ | — | — |
| ANR、卡顿、FPS、启动、慢方法 | ✅ | ✅（Trace Canary） | — |
| 文件 IO / Closeable / FD | ✅ | ✅（IO Canary） | — |
| SQLite 慢查询与 QueryPlan/Lint | ✅ | ✅（SQLite Lint） | — |
| 电量、线程活动与系统资源信号 | ◐（部分信号需宿主回调） | ✅（Battery Canary） | ◐（线程泄漏） |
| 网络请求阶段追踪 | ✅（OkHttp/手动接入） | — | — |
| WebView、Binder、GC、View/FrameMetrics | ◐（显式公共 API 接入） | ◐（部分相关能力） | — |
| APK 静态体积分析 | — | ✅（APK Checker） | — |
| 稳定 eventId + SQLite outbox + claim lease | ✅ | — | — |
| 独立 SDK 自诊断日志与导出 | ✅ | — | — |

Matrix 的强项是成熟的 Trace/IO/SQLite/Battery 与 Native Hook 体系；KOOM 专注 Java Heap、Native Heap 和线程泄漏；AndroidAPM 选择更宽的端上模块覆盖和可恢复传输链路。三者都不能仅凭客户端仓库等同于完整托管 APM 后台。

## SDK 自诊断

APM 自身的初始化、模块、dispatcher、存储和 uploader 日志会同时进入 Logcat 与独立本地诊断 journal。该 journal 不依赖 `ApmDispatcher`、事件 SQLite outbox 或 uploader，因此这些组件异常时仍可保留本地证据。

每个周期的 `sdk_health` 会先把仅含数值计数的摘要写入独立 journal，再以 HIGH 优先级尝试普通事件上报。即使 dispatcher 拥塞、采样或限流影响事件通道，本地仍有独立健康证据；该副本仍受诊断环与写队列的有界预算约束。

默认资源上限为：200 条 / 4 MiB 内存记录、256 条 / 4 MiB 非阻塞写队列、每个 Android 进程 3 个 512 KiB app-private JSONL 文件。进程目录由进程名和稳定哈希隔离；队列满时丢弃而不阻塞宿主，文件失败时先保留排队记录等待冷却重试，并降级为内存 + Logcat，且不会递归进入 APM logger。

显式 ZIP 导出同样采用 failure-as-data：即使自定义 diagnostic store 抛出文件异常，也返回 `DiagnosticExportResult(success=false)`，不会把诊断故障抛回宿主支持流程。

```kotlin
val status = ApmDiagnostics.status()
// snapshot/export 会解析文件，推荐使用调用方提供的工作线程。
ApmDiagnostics.snapshotAsync(executor, limit = 100) { recent -> /* render */ }
ApmDiagnostics.exportToAsync(
    executor,
    File(cacheDir, "android-apm-diagnostics.zip")
) { result -> /* share only after explicit user action */ }
ApmDiagnostics.clear()
// 多进程宿主需要显式清理所有进程证据时使用：
ApmDiagnostics.clearAllProcesses()
```

导出会聚合最近的最多 16 个进程目录，合并结果受 10,000 条 / 16 MiB 双上限约束，并拒绝覆盖任一活动 journal。ZIP manifest 包含格式/SDK 版本、进程名、诊断 session、健康计数以及是否发生截断；正文仅包含受控 SDK 字段和已脱敏、截断的异常信息，不复制事件 payload、业务上下文、请求正文或 SQL。SDK 默认不自动上传诊断包，分享与客服工单流程由接入方显式控制。

## 构建与验证

推荐直接使用 JDK 17。Gradle/AGP 兼容的更新 JDK 也可以启动构建，编译和测试任务仍通过 toolchain 固定使用 Java 17：

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat :apm-model:test
./gradlew.bat assembleDebug
./gradlew.bat -p apm-plugin test
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
```

发布链验证：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug
```

以 [AGENTS.md](AGENTS.md) 和 [项目文档](docs/Android_APM_项目文档.md) 中标注的日期判断哪些命令是当前 tip 的现场验证，不能把较早结果自动外推到新提交。

## 客户端完成边界

仓库内可实现的客户端缺口已经收口：稳定 `eventId`、SQLite v3 无损迁移、本地去重、并发 claim/lease/expiry、owner-aware ACK、单事件/总量 payload 预算、动态短期鉴权、签名配置/LKG/kill switch/采样/限流/endpoint、优先级感知入口背压、带迟滞恢复的 AutoThrottle、默认隐私保护、运行时配置/payload 快照、Binder/WebView/线程池显式公共 API、FPS 单调时间窗口、无逐帧对象分配的 FrameMetrics 滚动累计、`sdk_health` 双通道、SDK 自诊断和可编译 benchmark harness 均有源码与测试/构建入口。Sample 还实际接线 IO stream wrapper、`ApmSQLiteDatabase`、WebView install、IPC trace、线程池注册和 Battery 回调，可直接作为宿主接入参考。

仍需外部系统或真实设备的工作不伪装成“客户端未完成”：生产 Collector、租户/鉴权、服务端幂等、查询/聚合/告警/Dashboard、Native 后台符号化、外部制品发布、云端 CI，以及真机 soak/功耗/热/磁盘数值。完整协议、验收条件和推荐顺序统一记录在独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md`。

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
