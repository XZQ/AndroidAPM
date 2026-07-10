# Android APM Framework

模块化 Android 应用性能监控客户端 SDK。项目提供 15 个监控模块、统一事件管线、SQLite 持久化出箱、批量 HTTP 上传、手动 Trace API 和 OpenTelemetry 语义映射。

> 当前边界：本仓库负责 Android 端采集、保护、持久化和传输，不包含生产 Collector、查询/告警后台、Native 符号化服务或托管平台。

## 当前基线

- 同步日期：2026-07-10
- 24 个构建单元：22 个 root subproject + `apm-plugin`、`build-logic` 两个 included build
- 128 个主源码文件：123 Kotlin + 4 C + 1 proto
- 63 个测试文件
- Kotlin 2.2.21 / AGP 8.13.2 / Gradle 8.13 / JDK 21
- compileSdk 34 / minSdk 24 / targetSdk 34 / Java 11 字节码

详细状态见 [项目文档](docs/Android_APM_项目文档.md)，换机接手见 [交接快照](docs/PROJECT_HANDOFF.md)，模块设计见 [架构文档](docs/architecture/README.md)。

## 第一性原理架构

APM 客户端必须同时满足三件事：采集结果可信、监控开销受控、失败数据可恢复。因此运行时主链路是：

```text
监控模块
  -> Apm.emit（调用线程只捕获时间/线程/业务上下文快照）
  -> 有界队列 2048（满时丢弃，绝不阻塞业务线程）
  -> 可选聚合 -> 限流 -> 可选 PII 脱敏
  -> appendBatch（单轮最多 32 条）
  -> SQLite durable outbox（默认 50,000 行）
  -> PersistentUploadWorker
  -> BatchApmUploader / HttpApmUploader / 自定义 uploader
  -> 接入方 Collector
```

Crash 等关键事件可同步落盘，但不会在崩溃线程执行阻塞网络请求。非上传进程可选择通过 `.tmp` 写入、`.ipc` 发布的文件通道交给主进程。

上传成功后才删除 outbox 行；失败保留并指数退避，达到 10 次重试或超过 7 天后清理。这是至少一次语义：网络响应丢失时可能重复上传，服务端应支持去重。

## 模块组成

### 4 个基础模块

| 模块 | 职责 |
|---|---|
| `apm-model` | `ApmEvent`、priority/severity、Line Protocol、Protobuf、持久化 codec |
| `apm-core` | 初始化、模块生命周期、分发、限流、聚合、脱敏、多进程、自监控 |
| `apm-storage` | SQLite durable outbox；FileEventStore 兼容路径 |
| `apm-uploader` | HTTP/Logcat/自定义上传、批量、Gzip、Retry-After、内存重试兼容路径 |

### 15 个监控模块

| 模块 | 已实现能力 | 接入方式 |
|---|---|---|
| `apm-memory` | Heap/PSS/Native Heap、泄漏、OOM 预警、Hprof dump/裁剪 | 注册即采样；ViewModel 检查需调用 API；高风险 dump 默认关闭 |
| `apm-crash` | Java crash、可选 Native signal、tombstone、ApplicationExitInfo | Java 默认开启；Native crash 默认关闭 |
| `apm-anr` | `libapm-anr.so` SIGQUIT 标志 + Watchdog、堆栈采样、原因分类 | 注册后自动运行，Native 失败自动降级 Watchdog |
| `apm-launch` | 进程真实启动基线、冷/热/温启动、首帧、阶段跟踪 | Activity 生命周期自动；ContentProvider/App 阶段需宿主调用 |
| `apm-network` | OkHttp DNS→TCP→TLS→Body、慢请求和聚合 | 接入 Interceptor/EventListener，或手动回调 |
| `apm-fps` | Choreographer + FrameMetrics、掉帧分级 | Activity 生命周期自动 |
| `apm-slow-method` | Looper Hook、栈采样、ASM 方法插桩 | 运行时注册；ASM 需应用 `com.apm.slow-method` 插件 |
| `apm-io` | 流代理、主线程/慢 IO、FD/Closeable 泄漏、可选 PLT Hook | 包装流；Native 路径依赖运行时可解析 xhook |
| `apm-battery` | 电量下降、CPU Jiffies、WakeLock/GPS/Alarm 统计 | 电量/CPU 自动；其余需宿主转发生命周期 |
| `apm-sqlite` | 慢 SQL、主线程 DB、大影响行数、QueryPlan | 使用 `ApmSQLiteDatabase` 或手动回调 |
| `apm-webview` | 页面、JS、白屏、Bridge、Console、资源瀑布 | 宿主转发 WebView 回调；没有通用自动注册层 |
| `apm-ipc` | Binder 调用耗时、主线程阈值、聚合 | 调用 `onBinderCallComplete`；没有通用 Binder 自动 Hook |
| `apm-thread-monitor` | 线程数、同名线程、BLOCKED 状态 | 定时采样；没有线程池 backlog 自动插桩 |
| `apm-gc-monitor` | GC 次数/耗时、Heap 增长、分配率、回收率 | 定时读取运行时统计 |
| `apm-render` | View 数量和层级深度 | Activity 创建后遍历；过度绘制未实现 |

### 扩展与构建工具

| 模块 | 作用 |
|---|---|
| `apm-trace` | 手动 Span/Trace API，Span 结束后进入统一事件管线 |
| `apm-otel-exporter` | 把事件映射为 OTel-compatible Map；不依赖或发送到 OTel SDK |
| `apm-plugin` | AGP instrumentation + ASM，仅插桩宿主 project class |
| `build-logic` | 统一 Android library 的 compileSdk/minSdk/Java 配置 |
| `apm-sample-app` | 15 个监控模块的本地演示；默认输出到 Logcat，不代表生产后台闭环 |

## 快速接入

### 添加依赖

本地开发：

```kotlin
dependencies {
    implementation(project(":apm-memory"))
    implementation(project(":apm-network"))
}
```

发布到制品库后：

```kotlin
dependencies {
    implementation("com.apm:apm-memory:0.1.0")
    implementation("com.apm:apm-network:0.1.0")
}
```

当前仓库只验证过 `publishToMavenLocal` 和独立 Maven consumer；尚未发布 Maven Central 或外部私有制品库。

### 初始化并注册

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
| `endpoint` | 空 | 使用 Logcat uploader |
| `storageType` | `SQLITE` | 使用 durable outbox |
| `enableAggregation` | `false` | 不聚合客户端指标 |
| `enablePiiSanitization` | `false` | 不自动脱敏；生产接入应显式评审并开启 |
| `enableMultiProcessCoordination` | `false` | 不转发子进程事件 |
| `enableSelfMonitoring` | `true` | 周期上报 SDK 健康事件 |
| `enableAutoThrottle` | `true` | 健康恶化时可停用低优先级模块 |
| Native Crash | `false` | 避免默认启用高风险信号能力 |
| Hprof/fork dump | `false` | 避免默认产生大文件或依赖设备兼容性 |

## 构建与验证

必须使用 JDK 21：

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug
./gradlew.bat -p apm-plugin test
```

发布链验证：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug
```

以 [AGENTS.md](AGENTS.md) 和 [项目文档](docs/Android_APM_项目文档.md) 中标注的日期判断哪些命令是当前 tip 的现场验证，不能把较早结果自动外推到新提交。

## 当前未完成闭环

- 生产 Collector、鉴权、租户、查询、聚合、告警和 Dashboard
- eventId/idempotency 与服务端重复去除
- 多 worker/cross-process upload 的 claim/lease/expiry
- 真机长稳、功耗、磁盘和监控开销基准
- Native 符号表上传和 tombstone 后台符号化
- Maven Central/外部制品库发布
- IPC/WebView/线程池/Render 等声明型开关对应的完整自动实现
- 云端 CI；`.github/` 当前明确为本地忽略目录

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
