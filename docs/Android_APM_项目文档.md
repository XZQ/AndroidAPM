# Android APM 项目文档

> 最后校验：2026-04-20 | 21 个 Gradle 模块 | 85 个主源码文件 | 34 个测试文件 | `assembleDebug` / `testDebugUnitTest` 均通过
>
> 说明：当前代码实际为 `15` 个监控模块，不是旧文档中的 `16` 个；模块总数 `21 = 4` 个基础模块 + `15` 个监控模块 + `apm-plugin` + `apm-sample-app`

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
│  render | (apm-plugin: Gradle ASM 插桩插件)                           │
├───────────────────────────────────────────────────────────────────────┤
│                       Core 层（4 基础模块）                             │
│  apm-model    — 统一事件模型 (ApmEvent) + Line Protocol 序列化         │
│  apm-core     — 初始化/注册/分发/限流(令牌桶)/灰度/日志                  │
│  apm-storage  — 本地存储 (FileEventStore, lazy init, ring buffer)      │
│  apm-uploader — 上传通道 (重试/批量/指数退避)                           │
├───────────────────────────────────────────────────────────────────────┤
│                       Demo 层                                         │
│  apm-sample-app — 集成全部模块的示例应用                                 │
└───────────────────────────────────────────────────────────────────────┘
```

### 事件流

```
业务模块 → Apm.emit(module, name, kind, severity, fields)
         → ApmDispatcher（限流检查 → 本地存储 → 上传）
         → FileEventStore.append（ring buffer）
         → RetryingApmUploader → delegate.upload（批量 + 指数退避）
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
| Phase 6: 三大核心增强 | 已完成 | ANR SIGQUIT、ASM 字节码插桩、IO Native Hook |
| Phase 7: 测试覆盖 | 已完成 | 34 个测试文件，覆盖 Config + Module + Plugin 核心逻辑 |

### 1.2 Git 提交历史

```
372d7e7 Docs: Add mandatory MEMORY.md sync rule after each commit
2b73c8b Feat: Full project optimization - tests, version catalog, CI
eb1b9f2 Docs: Enforce English commit messages in CLAUDE.md
4a6a491 Refactor: minSdk 24 + centralized dependency management + package rename com.didi.apm -> com.apm
```

### 1.3 编码规范（CLAUDE.md 强制）

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
| **OOM 预警** | OomMonitor + HprofDumper + **fork 子进程 dump** + HprofStripProcessor | Hprof Stripper | **fork 子进程 dump** + Strip |
| **Crash** | UncaughtExceptionHandler + NativeCrashMonitor(**信号处理器** + Tombstone 降级) | Java + Native + ANR | - |
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
3. **ASM 字节码插桩**：Gradle Transform + ASM，编译期零侵入注入 methodEnter/methodExit
4. **IO Native PLT Hook**：拦截 libc open/read/write/close，Java 层自动降级
5. **IO 多维检测**：FD 泄漏 + 吞吐量统计 + Closeable 泄漏 + 零拷贝检测
6. **启动 6 阶段**：Application.attach → onCreate → Activity.onCreate → onResume → 首帧绘制 → 首帧渲染
7. **Native Crash 信号处理器**：sigaction 注册 6 个致命信号 + backtrace + fault addr + JNI 回调
8. **fork 子进程 Dump**：JNI fork 子进程执行 Debug.dumpHprofData，主进程零 STW
9. **引用链分析**：Hprof 二进制解析 → GC Root 扫描 → BFS 最短路径 → 完整引用链输出
10. **WebView 全链路**：JS Bridge 性能 + JS Console Error + 资源瀑布图 + 关键路径分析
11. **HTTP 上传通道**：HttpURLConnection + Line Protocol + Gzip 压缩 + 批量上报
12. **多进程支持**：ContentProvider 自动初始化 + 三级进程策略（MAIN/ALL/CUSTOM）

---

## 三、模块清单与源文件

### 3.1 基础设施层

| 模块 | 包名 | 核心类 | 源文件数 |
|------|------|--------|---------|
| apm-model | com.apm.model | ApmEvent, ApmEventKind, ApmSeverity, Line Protocol 序列化 | 1 |
| apm-core | com.apm.core | Apm, ApmModule, ApmConfig, ApmDispatcher, ApmLogger, ApmContext, ProcessUtils, ApmInitProvider, ProcessModuleFilter | 9 |
| apm-core/throttle | com.apm.core.throttle | RateLimiter(令牌桶), SampleController(灰度+采样) | 2 |
| apm-storage | com.apm.storage | EventStore, FileEventStore(lazy init, ring buffer) | 2 |
| apm-uploader | com.apm.uploader | ApmUploader, LogcatApmUploader, HttpApmUploader, RetryingApmUploader | 4 |

### 3.2 功能模块层

| 模块 | 包名 | 核心类 | 源文件数 |
|------|------|--------|---------|
| apm-memory | com.apm.memory | MemoryModule, MemorySampler, MemoryScheduler, MemorySnapshot, MemoryReporter, MemoryConfig | 17 + JNI |
| apm-crash | com.apm.crash | CrashModule, CrashConfig, NativeCrashMonitor(信号处理器+Tombstone降级) | 3 + JNI |
| apm-anr | com.apm.anr | AnrModule(SIGQUIT+Watchdog+traces.txt+分类+去重), AnrConfig | 2 |
| apm-launch | com.apm.launch | LaunchModule(6阶段冷启动+热启动+首帧), LaunchConfig | 2 |
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

### 3.3 构建工具层

| 模块 | 说明 | 源文件数 |
|------|------|---------|
| apm-plugin | Gradle 插件：ApmSlowMethodPlugin(Transform) + ApmClassTransformer(ASM) | 2 |

### 3.4 Demo 应用

| 模块 | 说明 |
|------|------|
| apm-sample-app | SampleApplication + MainActivity + LeakActivity，集成全部模块 |

---

## 四、关键模块详细设计

### 4.1 ANR 模块（增强后）

```
AnrModule（双重检测 + 原因分类 + traces.txt + 去重）
├── SIGQUIT 信号检测
│   └── 注册 SIGQUIT handler，收到信号 → 触发 ANR 分析
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
└── 编译期：apm-plugin（Gradle Transform + ASM）
    ├── ApmSlowMethodPlugin — Gradle 插件入口
    │   └── 注册 Transform + Extension（配置包名过滤、阈值）
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
├── Level 2: Native PLT Hook（需 libapm-io.so）
│   ├── System.loadLibrary("apm-io") 加载 JNI 库
│   ├── nativeInstallIoHooks() 安装 Hook
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

apm-plugin（独立 Gradle 插件，编译期使用，不参与运行时依赖）
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
| Native Crash 信号处理 | 已完成 | JNI 信号处理器（SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGPIPE/SIGSTKFLT）+ backtrace 捕获 + fault addr + 回调 Java 层上报 + Tombstone 降级方案 |
| fork 子进程 dump | 已完成 | JNI fork 子进程 dump（避免主进程 STW）+ 父进程 waitpid 等待 + 失败自动降级直接 dump |
| 多进程支持 | 已完成 | ApmInitProvider ContentProvider 自动初始化 + ProcessModuleFilter 进程隔离 + ProcessStrategy 三策略（MAIN_PROCESS_ONLY/ALL_PROCESSES/CUSTOM） |
| 引用链分析 | 已完成 | ReferenceChainAnalyzer 完整 Hprof 二进制解析：header 解析 → GC Root 扫描 → Class/Instance Dump → BFS 最短路径 → 引用链输出 |
| WebView 监控 | 已完成 | JS Bridge 调用性能监控 + JS Console Error 拦截 + 资源加载瀑布图（ResourceWaterfall）+ 慢资源告警 + 关键路径分析 |
| libapm-io.so JNI 库 | 已完成 | C 源码实现 PLT Hook（xhook/bhook）拦截 open/openat/read/write/close + IO 会话管理 + Java 回调 |
| 上传通道实现 | 已完成 | HttpApmUploader 基于 HttpURLConnection：Line Protocol 批量上报 + 自定义 Headers + Gzip 压缩 + 超时配置 |
| 零拷贝检测 | 已完成 | NativeIoHook CopyChain 追踪 + 平均 buffer 数阈值检测 + 零拷贝优化建议上报（FileChannel.transferTo/sendfile） |
| Native Crash JNI 库 | 已完成 | apm_crash_jni.c 完整实现：sigaction 注册 + 信号回调 + JNI_OnLoad 缓存 + CMakeLists.txt |
| Fork dump JNI 库 | 已完成 | apm_dumper_jni.c 完整实现：fork 子进程 + Debug.dumpHprofData JNI 调用 + CMakeLists.txt |

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
| Gradle 模块数 | 20（16 功能 + apm-plugin + apm-sample-app + apm-model + apm-core + apm-storage + apm-uploader） |
| Kotlin 源文件 | 67 |
| 测试类 | 25 |
| 总代码行数 | ~8000+ |
| 编译结果 | BUILD SUCCESSFUL |
| 测试结果 | 全部通过 |
