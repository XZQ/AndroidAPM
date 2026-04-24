# Android APM 项目文档

> 最后校验：2026-04-24 | `22` 个 root Gradle subproject + `1` 个 included build | 163 个主源码文件 | 51 个测试文件 | `assembleDebug` / `testDebugUnitTest` / `./gradlew -p apm-plugin test` 本轮均已通过
>
> 说明：构建单元总数 `23 = 22` 个 root subproject（`4` 个基础模块 + `15` 个监控模块 + `2` 个扩展模块（apm-trace, apm-otel-exporter）+ `apm-sample-app`）+ `1` 个 included build（`apm-plugin`）

---

## 零、快速上手（新接手必读）

### 项目是什么

一个 Android 应用性能监控（APM）框架，对标微信 Matrix + 快手 KOOM + Google 最佳实践，当前代码覆盖内存、崩溃、ANR、启动、网络、FPS、慢方法、IO、电量、SQLite、WebView、IPC、线程、GC、渲染共 **15 个监控模块**。

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 11 |
| Android Gradle Plugin | 7.4.2 |
| Kotlin | 1.8.10 |
| compileSdk | 34 |
| minSdk | 24 |
| targetSdk | 34 |

### 构建命令

```bash
# 编译全部模块
JAVA_HOME=/home/didi/.jdks/jbr_dcevm-11.0.16 ./gradlew assembleDebug

# 运行单元测试
JAVA_HOME=/home/didi/.jdks/jbr_dcevm-11.0.16 ./gradlew testDebugUnitTest

# 如果系统已安装 JDK 11，可直接：
./gradlew assembleDebug testDebugUnitTest
```

### 30 秒看架构

```
┌───────────────────────────────────────────────────────────────────────┐
│                       Feature 层（15 功能模块）                        │
│  memory | crash | anr | launch | network | fps | slow-method | io     │
│  thread-monitor | battery | sqlite | webview | ipc | gc-monitor       │
│  render                                                              │
├───────────────────────────────────────────────────────────────────────┤
│                       Core 层（4 基础模块）                             │
│  apm-model    — 统一事件模型 (ApmEvent) + Line Protocol + Protobuf     │
│  apm-core     — 初始化/注册/分发/限流/聚合/脱敏/自监控                   │
│  apm-storage  — 本地存储 (File + SQLite 50K, WAL, 优先级淘汰)          │
│  apm-uploader — 上传通道 (重试/批量/优先级队列/指数退避)                  │
├───────────────────────────────────────────────────────────────────────┤
│                       Extension 层（2 扩展模块）                        │
│  apm-trace          — 手动埋点 Span/Trace API                         │
│  apm-otel-exporter  — OpenTelemetry 桥接 (Span/Metric/Log)            │
├───────────────────────────────────────────────────────────────────────┤
│                       Demo / Build Tool 层                            │
│  apm-sample-app — 集成全部模块的示例应用                                 │
│  apm-plugin     — included build，本地 Gradle ASM 插桩插件              │
└───────────────────────────────────────────────────────────────────────┘
```

### 事件流

```
业务模块 → Apm.emit(module, name, kind, severity, priority, fields)
         → ApmDispatcher（聚合 → 限流 → PII脱敏 → 本地存储 → 上传）
         → EventStore（SQLite 50K WAL / File ring buffer）
         → RetryingApmUploader（优先级队列 → 批量 + 指数退避）
```

---

## 一、项目当前状态

### 1.1 进度总览

| 阶段 | 状态 | 说明 |
|------|------|------|
| Phase 1: 基础框架 | 已完成 | apm-model, apm-core, apm-storage, apm-uploader |
| Phase 2: 核心模块 | 已完成 | memory, crash, anr, launch, network |
| Phase 3: 扩展模块 | 已完成 | fps, slow-method, io, thread-monitor, battery, sqlite, webview, ipc |
| Phase 4: 高级模块 | 已完成 | gc-monitor, render |
| Phase 5: 全面对标重构 | 已完成 | 15 个监控模块对标微信 Matrix + KOOM + Google 最佳实践 |
| Phase 6: 三大核心增强 | 已完成 | ANR SIGQUIT、ASM 字节码插桩、IO Native Hook；sample 已完成 slow-method 插件接线 |
| Phase 7: 测试覆盖 | 已完成 | 40 个测试文件，覆盖 Config + Module + Plugin 核心逻辑 |
| Phase 8: 生产级序列化 | 已完成 | Protobuf 序列化 + 客户端事件聚合 + PII 脱敏 |
| Phase 9: 生产级存储 | 已完成 | SQLite 50K 存储 + 优先级队列 + 多进程协调 + SDK 自监控 |
| Phase 10: Trace API | 已完成 | apm-trace 手动埋点 Span/Trace API |
| Phase 11: OTel 对接 | 已完成 | apm-otel-exporter OpenTelemetry 桥接 |

### 1.2 Git 提交历史

```
de499c6 Refactor: Align slow method plugin extension
ee168a6 Refactor: Harden native hooks and migrate slow method plugin
117bafa Fix: Repair uploader lifecycle and measurement semantics
372d7e7 Docs: Add mandatory MEMORY.md sync rule after each commit
2b73c8b Feat: Full project optimization - tests, version catalog, CI
```

### 1.3 当前已验证关键接线

1. `Apm.startModule()` 已接入 `ProcessModuleFilter`，`ProcessStrategy.CUSTOM` 不再只是定义存在。
2. `CrashModule` 已消费 `enableNativeCrash`，启动时先扫描 tombstone，Native 信号处理默认安全重抛；调试场景可显式开启 unsafe JNI 回调。
3. `IoModule` 已驱动 `NativeIoHook.init()/destroy()`，`apm-io` 已接入 CMake 构建 `libapm-io.so`，运行时动态解析 `libxhook.so`，缺失时自动降级 Java 代理。
4. `apm-plugin` 已从 legacy Transform 迁移到 AGP instrumentation API，ASM tracer 目标类为 `com.apm.slowmethod.ApmSlowMethodTracer`。
5. `apm-sample-app` 已通过 `pluginManagement { includeBuild("apm-plugin") }` 应用 `com.apm.slow-method`。
6. `gradle.properties` 已移除 `android.experimental.legacyTransform.forceNonIncremental`，slow-method 插件不再依赖旧 Transform 兼容开关。
7. `ApmConfig` 已支持显式注入 `uploader`；未注入时按 `endpoint` 自动选择 `HttpApmUploader` 或 `LogcatApmUploader`。
8. `RetryingApmUploader` 已将 `delegate.upload()` 返回 `false` 也纳入重试判定，`Apm.stop()` 同时关闭 dispatcher 和 uploader。
9. `AnrModule` 的 SIGQUIT 路径已切到独立分析线程，不再依赖主线程消息队列恢复后才处理。
10. `LaunchModule` 的热/温启动已改为“后台停留时长决定类型，前台恢复链路耗时决定上报值”，并附带 `backgroundDurationMs`。
11. `FileEventStore` 已改为按真实 append 次数触发重写，避免缓冲区打满后每次 append 都整文件重写。
12. `apm-core` 已将 `apm-uploader` 提升为 `api` 依赖，保证 `ApmConfig.uploader` 暴露的公开类型能被下游应用正常编译解析。

### 1.4 编码规范（CLAUDE.md 强制）

1. **规则一**：所有 `public/internal/private` 成员变量和方法必须添加 KDoc 注释
2. **规则二**：方法内分支/循环/异常/回调等关键节点必须添加行内注释
3. **规则三**：所有常量提取为 `private const val` 或 `companion object` 命名常量，禁止裸数字/裸字符串

---

## 二、功能对比矩阵

| 功能 | 我们的实现 | 微信 Matrix | 快手 KOOM | Google 最佳实践 |
|------|-----------|------------|-----------|----------------|
| **FPS / 卡顿** | Choreographer VSync + 掉帧/卡顿/冻结分级 | Choreographer 回调 | - | 推荐 (Jank) |
| **慢方法** | 反射 Hook Looper.mLogging + **ASM 字节码插桩** + 热点方法统计 | 方法插桩 + 栈采样 | - | 推荐 |
| **IO 监控** | 主线程 IO + 大 buffer + **Native PLT Hook** + FD 泄漏 + 吞吐量 + Closeable 泄漏 + **零拷贝检测** | File I/O 耗时统计 | - | 推荐 |
| **ANR** | **SIGQUIT 信号检测** + Watchdog 双重检测 + **traces.txt 解析** + 5 种原因分类 + 堆栈采样 + 去重 | Watchdog + 堆栈分析 | - | - |
| **内存泄漏** | WeakRef + Activity/Fragment/ViewModel 泄漏 + **引用链分析**(Hprof BFS) | Activity/Fragment/Root | 监控 + 自动回收 |
| **OOM 预警** | OomMonitor + HprofDumper + **fork 子进程 dump（显式开启）** + HprofStripProcessor | Hprof Stripper | **fork 子进程 dump** + Strip |
| **Crash** | UncaughtExceptionHandler + NativeCrashMonitor(**安全信号重抛** + Tombstone 降级，unsafe JNI 回调可选) | Java + Native + ANR | - |
| **启动耗时** | 冷启动 + 热启动 + 温启动（6 阶段分阶段上报） + Choreographer 首帧检测 | 冷/热/温启动 | - | - |
| **网络监控** | onRequestComplete + OkHttp Interceptor + EventListener + 聚合统计 | 全链路 | - | - |
| **电量** | WakeLock 追踪 + 电量下降速率 + 广播监听 + CPU Jiffies | Battery 监控 | - | 推荐 |
| **SQLite** | 慢查询 + 主线程 DB + 大数据量 + QueryPlan 分析 | 慢查询/锁等待 | - | - |
| **WebView** | 页面加载耗时 + JS 执行耗时 + 白屏检测 + **JS Bridge 监控** + **资源瀑布图** | 页面加载耗时 | - | - |
| **线程监控** | 线程数膨胀 + 同名泄漏 + BLOCKED 死锁 | 线程泄漏/死锁 | 线程泄漏检测 | 推荐 |
| **IPC/Binder** | Binder 调用耗时 + 主线程阈值分级 | Binder 调用耗时 | - | - |
| **GC 监控** | GC 统计采集 + GC 次数飙升/耗时占比/Heap 增长检测 | GC 频繁触发检测 | - | 推荐 |
| **渲染监控** | View 树遍历 + View 数量/层级深度检测 | Overdraw 检测 | - | 推荐 |

### 核心增强亮点（超越 Matrix）

1. **ANR 双重检测**：SIGQUIT 信号 + Watchdog 线程互补，不漏检不误报
2. **ANR 原因分类**：自动归因为 CPU/IO/LOCK/DEADLOCK/BINDER 五类
3. **ASM 字节码插桩**：AGP instrumentation API + ASM，编译期零侵入注入 methodEnter/methodExit
4. **IO Native PLT Hook**：CMake 构建 `libapm-io.so`，运行时动态解析 xhook 拦截 libc open/read/write/close，Java 层自动降级
5. **IO 多维检测**：FD 泄漏 + 吞吐量统计 + Closeable 泄漏 + 零拷贝检测
6. **启动 6 阶段**：Application.attach → onCreate → Activity.onCreate → onResume → 首帧绘制 → 首帧渲染
7. **Native Crash 信号处理器**：sigaction 注册 6 个致命信号，默认恢复原 handler 并重抛生成 tombstone，调试可选 unsafe JNI 回调
8. **fork 子进程 Dump**：JNI fork 子进程执行 Debug.dumpHprofData，需显式开启并带失败降级
9. **引用链分析**：Hprof 二进制解析 → GC Root 扫描 → BFS 最短路径 → 完整引用链输出
10. **WebView 全链路**：JS Bridge 性能 + JS Console Error + 资源瀑布图 + 关键路径分析
11. **HTTP 上传通道**：HttpURLConnection + Line Protocol + Gzip 压缩 + 批量上报
12. **多进程支持**：ContentProvider 自动初始化 + 三级进程策略（MAIN/ALL/CUSTOM）
13. **Protobuf 序列化**：零依赖 wire format 写入器，体积约为 Line Protocol 1/3~1/5
14. **客户端聚合**：METRIC 滑动窗口 P50/P90/P99 + ALERT 栈指纹去重
15. **PII 脱敏**：内置 5 条正则规则（手机号/邮箱/身份证/URL token/密码）
16. **SQLite 50K 存储**：WAL 模式 + 优先级水位线淘汰
17. **SDK 自监控**：emit/drop/latency 计数 + 自动降级
18. **手动埋点 Trace API**：W3C 兼容 128-bit traceId + 嵌套 Span
19. **OpenTelemetry 桥接**：ALERT→Span、METRIC→Gauge、全类型→LogRecord

---

## 三、模块清单与源文件

### 3.1 基础设施层

| 模块 | 包名 | 核心类 | 源文件数 |
|------|------|--------|---------|
| apm-model | com.apm.model | ApmEvent, ApmEventKind, ApmSeverity, ApmPriority, Line Protocol, ProtobufSerializer, ProtobufWriter | 6 |
| apm-core | com.apm.core | Apm, ApmModule, ApmConfig, ApmDispatcher, ApmLogger, ApmContext, ProcessUtils, ApmInitProvider, ProcessModuleFilter, UploaderFactory | 10 |
| apm-core/throttle | com.apm.core.throttle | RateLimiter(令牌桶), SampleController(灰度+采样) | 2 |
| apm-core/aggregation | com.apm.core.aggregation | EventAggregator(滑动窗口), StackFingerprinter(栈指纹), AggregatedEvent | 3 |
| apm-core/privacy | com.apm.core.privacy | PiiSanitizer, SanitizationRule, DefaultSanitizationRules | 3 |
| apm-core/selfmonitor | com.apm.core.selfmonitor | SdkSelfMonitor, SdkHealthReport, AutoThrottle | 3 |
| apm-storage | com.apm.storage | EventStore, FileEventStore, SQLiteEventStore(50K WAL), EventDbHelper | 4 |
| apm-uploader | com.apm.uploader | ApmUploader, LogcatApmUploader, HttpApmUploader(LP+Protobuf), RetryingApmUploader(优先级队列) | 4 |

### 3.2 功能模块层

| 模块 | 包名 | 核心类 | 源文件数 |
|------|------|--------|---------|
| apm-memory | com.apm.memory | MemoryModule, MemorySampler, MemoryScheduler, MemorySnapshot, MemoryReporter, MemoryConfig | 17 + JNI |
| apm-crash | com.apm.crash | CrashModule, CrashConfig, NativeCrashMonitor(信号处理器+Tombstone降级) | 3 + JNI |
| apm-anr | com.apm.anr | AnrModule(SIGQUIT+Watchdog+traces.txt+分类+去重), SigquitAnalysisDispatcher, AnrConfig | 3 |
| apm-launch | com.apm.launch | LaunchModule(6阶段冷启动+热启动+首帧), RelaunchTracker, LaunchConfig | 3 |
| apm-network | com.apm.network | NetworkModule, ApmNetworkInterceptor, ApmEventListener, NetworkConfig, NetworkStats, NetworkRequestStats | 6 |
| apm-fps | com.apm.fps | FpsModule, FpsMonitor(Choreographer VSync), FpsConfig, FrameStats | 4 |
| apm-slow-method | com.apm.slowmethod | SlowMethodModule, ApmSlowMethodTracer(ASM运行时), StackSamplingProfiler, SlowMethodConfig | 4 |
| apm-io | com.apm.io | IoModule, NativeIoHook(PLT Hook+FD+吞吐量+零拷贝), IoConfig | 3 + JNI |
| apm-thread-monitor | com.apm.threadmonitor | ThreadMonitorModule, ThreadMonitorConfig | 2 |
| apm-battery | com.apm.battery | BatteryModule, BatteryConfig, CpuJiffiesSampler | 3 |
| apm-sqlite | com.apm.sqlite | SqliteModule, SqliteConfig, QueryPlanAnalyzer | 3 |
| apm-webview | com.apm.webview | WebviewModule(JS Bridge+Console Error), WebviewConfig, ResourceWaterfall | 3 |
| apm-ipc | com.apm.ipc | IpcModule, IpcConfig | 2 |
| apm-gc-monitor | com.apm.gcmonitor | GcMonitorModule, GcStats, GcMonitorConfig | 3 |
| apm-render | com.apm.render | RenderModule, RenderConfig, RenderStats | 3 |

### 3.3 扩展模块层

| 模块 | 包名 | 核心类 | 源文件数 |
|------|------|--------|---------|
| apm-trace | com.apm.trace | ApmTrace, ApmSpan, SpanContext, TraceConfig, IdGenerator | 5 |
| apm-otel-exporter | com.apm.otel | OtelEventBridge, OtelSpanExporter, OtelMetricExporter, OtelConfig | 4 |

### 3.4 构建工具层

| 模块 | 说明 | 源文件数 |
|------|------|---------|
| apm-plugin | included build：ApmSlowMethodPlugin(AGP instrumentation) + ApmClassTransformer(ASM) | 2 |

### 3.5 Demo 应用

| 模块 | 说明 |
|------|------|
| apm-sample-app | SampleApplication + MainActivity + LeakActivity，集成全部模块 |

---

## 四、关键模块详细设计

### 4.1 ANR 模块（增强后）

```
AnrModule（双重检测 + 原因分类 + traces.txt + 去重）
├── SIGQUIT 信号检测
│   └── 注册 SIGQUIT handler，收到信号 → 调度到独立分析线程执行 ANR 分析
├── Watchdog 线程
│   └── 主线程 tick 标记，超时未响应 → 触发 ANR 分析
├── ANR 原因分类
│   ├── CPU — CPU 占用过高
│   ├── IO — 磁盘 IO 阻塞
│   ├── LOCK — 锁竞争
│   ├── DEADLOCK — 死锁
│   └── BINDER — Binder 调用阻塞
├── traces.txt 读取
│   └── 解析 /data/anr/traces.txt 获取精确堆栈
├── 堆栈采样
│   └── ANR 期间采样主线程堆栈（可配置间隔和次数）
└── 去重机制
    └── 基于 stackTraceHash 去重，避免重复上报
```

### 4.2 慢方法模块（增强后）

```
SlowMethodModule
├── 运行时：ApmSlowMethodTracer（object 单例）
│   ├── methodEnter(signature) — ThreadLocal<Stack> 记录进入时间戳
│   ├── methodExit(signature) — 计算耗时，超阈值上报
│   ├── 热点方法统计 — ConcurrentHashMap<signature, HotMethodInfo>
│   └── 严重告警分级 — >= 300ms WARN, >= 800ms ERROR
└── 编译期：apm-plugin（AGP instrumentation + ASM）
    ├── ApmSlowMethodPlugin — Gradle 插件入口
    │   └── 注册 AsmClassVisitorFactory + Extension（配置插桩开关、包名过滤）
    └── ApmClassTransformer — ASM ClassVisitor
        └── AdviceAdapter 注入 methodEnter/methodExit 调用
```

### 4.3 IO 模块（增强后）

```
IoModule + NativeIoHook
├── Level 1: Java 层代理（默认，零依赖）
│   ├── wrapInputStream / wrapOutputStream — 代理流操作
│   ├── onRead — 小 buffer 检测 + 重复读检测
│   ├── onClose — 主线程 IO 耗时检测
│   └── 自动降级（Native Hook 不可用时）
├── Level 2: Native PLT Hook（需 libapm-io.so + 可解析 libxhook.so）
│   ├── System.loadLibrary("apm-io") 加载 JNI 库
│   ├── nativeInstallIoHooks() 动态解析 xhook 并安装 Hook
│   └── JNI 回调 onNativeIoEvent() 上报
├── FD 泄漏检测
│   ├── /proc/self/fd 目录扫描
│   ├── FD 分配/释放计数器
│   └── 超阈值上报泄漏路径列表
├── 吞吐量统计
│   ├── 全局 read/write 字节计数
│   └── 按路径聚合 (ConcurrentHashMap<path, ThroughputStats>)
└── Closeable 泄漏检测
    └── PhantomReference + ReferenceQueue 追踪未 close 的流
```

### 4.4 Memory 模块

```
apm-memory/
├── MemoryModule        — 模块入口，整合所有子组件
├── MemoryConfig        — 全部配置项（采样间隔/阈值/开关）
├── MemorySampler       — Java Heap/PSS/NativeHeap/VmRSS/GC 统计采集
├── MemoryScheduler     — 前后台定时轮询（ProcessLifecycleOwner）
├── MemorySnapshot      — 采集数据模型
├── MemoryReporter      — 事件上报 + OOM 预警联动
├── leak/
│   ├── ActivityLeakDetector  — WeakRef + ReferenceQueue + 延迟 GC
│   ├── FragmentLeakDetector  — FragmentManager 生命周期回调
│   ├── ViewModelLeakDetector — 反射检测 Context/View 持有
│   └── LeakResult            — 泄漏结果数据类
├── oom/
│   ├── OomMonitor         — AtomicLong/AtomicBoolean 线程安全
│   ├── HprofDumper        — dump 触发
│   └── HprofStripProcessor — Hprof 裁剪
└── nativeheap/
    ├── NativeHeapMonitor  — Debug.getNativeHeapAllocatedSize
    └── NativeHeapStats    — Native 堆统计数据类
```

---

## 五、模块依赖关系

```
apm-sample-app
  ├── apm-memory
  ├── apm-crash
  ├── apm-anr
  ├── apm-launch
  ├── apm-network
  ├── apm-fps
  ├── apm-slow-method
  ├── apm-io
  ├── apm-thread-monitor
  ├── apm-battery
  ├── apm-sqlite
  ├── apm-webview
  ├── apm-ipc
  ├── apm-gc-monitor
  ├── apm-render
  └── apm-core
        ├── apm-model
        ├── apm-storage
        └── apm-uploader

apm-trace → apm-core → apm-model
apm-otel-exporter → apm-core (compileOnly), OTel SDK (compileOnly)

apm-plugin（独立 Gradle included build，编译期使用，不参与运行时依赖）
```

---

## 六、测试覆盖

| 模块 | 测试文件 | 测试内容 |
|------|---------|---------|
| apm-model | ApmEventTest | Line Protocol 序列化 |
| apm-core | RateLimiterTest | 令牌桶限流逻辑 |
| apm-core | ApmConfigTest | 配置默认值 |
| apm-uploader | RetryPolicyTest | 重试策略 |
| apm-memory | MemoryConfigTest | 配置验证 |
| apm-crash | CrashConfigTest | 配置验证 |
| apm-anr | AnrConfigTest | 配置验证 |
| apm-launch | LaunchConfigTest | 配置验证 |
| apm-network | NetworkConfigTest | 配置验证 |
| apm-battery | BatteryConfigTest | 配置验证 |
| apm-fps | FpsConfigTest | 配置验证 |
| apm-gc-monitor | GcMonitorConfigTest | 配置验证 |
| apm-io | IoConfigTest | 配置验证 |
| apm-ipc | IpcConfigTest, IpcModuleTest | 配置 + 模块逻辑 |
| apm-render | RenderConfigTest | 配置验证 |
| apm-slow-method | SlowMethodConfigTest | 配置验证 |
| apm-sqlite | SqliteConfigTest | 配置验证 |
| apm-thread-monitor | ThreadMonitorConfigTest, ThreadMonitorModuleTest | 配置 + 模块逻辑 |
| apm-webview | WebviewConfigTest, WebviewModuleTest | 配置 + 模块逻辑 |

---

## 七、设计原则

1. **先平台，后能力** — 统一框架做稳，每个专项复用基础设施
2. **先 Java 层可用，再 Native 层增强** — 优先稳定采样、预警、事件上报
3. **先离线闭环，再线上放量** — 本地可观测、日志可读、事件可验证，再接线上上传
4. **先主进程，后多进程** — 多进程支持有，第一阶段不追求全进程全功能
5. **先低风险能力，后高侵入能力** — Leak / OOM 预警优先于 Native Hook
6. **自动降级** — Native Hook 不可用时自动降级为 Java 层代理

---

## 八、待完善项

### 8.1 全部已完成

| 功能 | 状态 | 说明 |
|------|------|------|
| Hprof 裁剪实现 | 已完成 | HprofStripProcessor 完整实现：二进制解析、record 处理、primitive array 清零、GC Root 处理 |
| HTTP 全链路 trace | 已完成 | ApmEventListener 完整追踪 DNS→TCP→TLS→RequestHeaders→ResponseHeaders→ResponseBody，纳秒级精度 |
| Native Crash 信号处理 | 已完成 | JNI 信号处理器（SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGPIPE/SIGSTKFLT）+ 默认安全重抛生成 Tombstone + 启动扫描 Tombstone；unsafe JNI 回调仅调试显式开启 |
| fork 子进程 dump | 已完成 | JNI fork 子进程 dump + 父进程 waitpid 等待 + 失败自动降级直接 dump；默认关闭，需通过 `enableForkHprofDump` 显式开启 |
| 多进程支持 | 已完成 | ApmInitProvider ContentProvider 自动初始化 + ProcessModuleFilter 进程隔离 + ProcessStrategy 三策略（MAIN_PROCESS_ONLY/ALL_PROCESSES/CUSTOM） |
| 引用链分析 | 已完成 | ReferenceChainAnalyzer 完整 Hprof 二进制解析：header 解析 → GC Root 扫描 → Class/Instance Dump → BFS 最短路径 → 引用链输出 |
| WebView 监控 | 已完成 | JS Bridge 调用性能监控 + JS Console Error 拦截 + 资源加载瀑布图（ResourceWaterfall）+ 慢资源告警 + 关键路径分析 |
| libapm-io.so JNI 库 | 已完成 | `apm-io` 已接入 CMake 构建，C 源码动态解析 `libxhook.so` 后拦截 open/openat/read/write/close；缺少 xhook 时明确异常并回落 Java 代理 |
| 上传通道实现 | 已完成 | HttpApmUploader 基于 HttpURLConnection：Line Protocol 批量上报 + 自定义 Headers + Gzip 压缩 + 超时配置 |
| 零拷贝检测 | 已完成 | NativeIoHook CopyChain 追踪 + 平均 buffer 数阈值检测 + 零拷贝优化建议上报（FileChannel.transferTo/sendfile） |
| Native Crash JNI 库 | 已完成 | apm_crash_jni.c 完整实现：sigaction 注册 + 安全重抛默认路径 + 可选 unsafe 回调 + JNI_OnLoad 缓存 + CMakeLists.txt |
| Fork dump JNI 库 | 已完成 | apm_dumper_jni.c 完整实现：fork 子进程 + Debug.dumpHprofData JNI 调用 + 子进程失败状态回传 + CMakeLists.txt |

---

## 九、开源参考

| 项目 | 学习重点 |
|------|----------|
| 微信 Matrix | ResourceCanary, TraceCanary, IOCanary, SQLiteLint |
| 快手 KOOM | fork dump 完整实现, Hprof Stripper, 自动回收 |
| 字节 bhook | Native PLT Hook（ELF .dynsym 表替换） |
| LeakCanary/Shark | Hprof 解析、引用链分析 |
| Perfetto | 系统级 trace |
| Facebook Profilo | 高频方法采样、Native 栈回溯 |

---

## 十、项目统计

| 指标 | 数值 |
|------|------|
| 构建单元数 | 23（22 个 root Gradle subproject + 1 个 included build: apm-plugin） |
| Kotlin 源文件 | 163 |
| 测试文件 | 51 |
| 总代码行数 | ~12000+ |
| 编译结果 | `assembleDebug` 通过 |
| 测试结果 | `testDebugUnitTest` + `./gradlew -p apm-plugin test` 通过 |
