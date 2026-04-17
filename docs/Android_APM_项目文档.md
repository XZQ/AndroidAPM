# Android APM 项目文档

> 最后更新：2026-04-16 | 20 模块 | 67 源文件 | 129 测试 | BUILD SUCCESSFUL

---

## 一、功能对比矩阵

| 功能 | 我们 | 微信 Matrix | 快手 KOOM | Google 最佳实践 |
|------|------|------------|-----------|----------------|
| FPS / 卡顿检测 | Choreographer VSync 回调 + 掉帧/卡顿/冻结分级 | Choreographer 回调 | - | 推荐 (Jank) |
| 慢方法检测 | 反射 hook Looper.mLogging + 堆栈捕获 | 方法插桩 + 栈采样 | - | 推荐 |
| IO 监控 | 主线程 IO 耗时 + 大 buffer 检测 + 堆栈 | File I/O 耗时统计 | - | 推荐 |
| 电量监控 | WakeLock 追踪 + 电量下降速率 + 广播监听 | Battery 监控 | - | 推荐 |
| SQLite 监控 | 慢查询 + 主线程 DB + 大数据量操作 | 慢查询/锁等待 | - | - |
| WebView 监控 | 页面加载耗时 + JS 执行耗时 + 白屏检测 | 页面加载耗时 | - | - |
| 线程监控 | 线程数膨胀 + 同名泄漏 + BLOCKED 死锁 | 线程泄漏/死锁 | 线程泄漏检测 | 推荐 |
| IPC/Binder 监控 | Binder 调用耗时 + 主线程阈值分级 | Binder 调用耗时 | - | - |
| 内存分配频率 | GC 统计采集 + GC 次数飙升/耗时占比/Heap 增长检测 | GC 频繁触发检测 | - | 推荐 |
| 渲染过度绘制 | View 树遍历 + View 数量/层级深度检测 | Overdraw 检测 | - | 推荐 |
| 内存泄漏 | WeakRef 检测 + 反射引用链分析（Context/View/Handler） | Activity/Fragment/Root | 监控 + 自动回收 | - |
| Hprof 裁剪 | HprofStripProcessor 完整实现（primitive array 清零） | Hprof Stripper | Hprof Stripper + 上传 | - |
| Crash | UncaughtExceptionHandler + NativeCrashMonitor（信号解析+tombstone降级） | Java + Native + ANR | - | - |
| ANR | Watchdog 线程 + tick 标记 + 主线程堆栈 | Watchdog + 堆栈分析 | - | - |
| 启动耗时 | 冷启动 + 热启动 + 温启动（分阶段上报） | 冷/热/温启动 | - | - |
| 网络监控 | onRequestComplete 回调 + OkHttp Interceptor 自动采集 + 聚合统计 | 全链路 | - | - |

---

## 二、模块清单

### 2.1 基础设施层

| 模块 | 核心类 | 状态 |
|------|--------|------|
| apm-model | ApmEvent、ApmEventKind、ApmSeverity、line protocol 序列化 | 已完成 |
| apm-core | Apm.init/register/emit、ApmConfig、ApmDispatcher、ApmLogger | 已完成 |
| apm-storage | EventStore 接口、FileEventStore（lazy init、ring buffer） | 已完成 |
| apm-uploader | ApmUploader 接口、LogcatApmUploader、RetryingApmUploader（指数退避） | 已完成 |

### 2.2 核心限流层（apm-core/throttle）

| 类 | 说明 | 状态 |
|------|------|------|
| RateLimiter | 令牌桶限流，按 module/name 分桶 | 已完成 |
| DynamicConfigProvider | 动态配置接口（对接 Apollo/Firebase） | 已完成 |
| GrayReleaseController | 灰度发布，功能开关 + 采样率控制 | 已完成 |

### 2.3 功能模块层

| 模块 | 包名 | 核心类 | 状态 |
|------|------|--------|------|
| apm-memory | com.didi.apm.memory | MemoryModule、MemorySampler、MemoryScheduler、MemorySnapshot、MemoryReporter、MemoryConfig | 已完成 |
| apm-crash | com.didi.apm.crash | CrashModule（UncaughtExceptionHandler）、CrashConfig、NativeCrashMonitor | 已完成 |
| apm-anr | com.didi.apm.anr | AnrModule（Watchdog thread）、AnrConfig | 已完成 |
| apm-launch | com.didi.apm.launch | LaunchModule（冷/热启动）、LaunchConfig | 已完成 |
| apm-network | com.didi.apm.network | NetworkModule（请求监控）、NetworkConfig、NetworkStats | 已完成 |
| apm-fps | com.didi.apm.fps | FpsModule、FpsMonitor（Choreographer VSync）、FpsConfig、FrameStats | 已完成 |
| apm-slow-method | com.didi.apm.slowmethod | SlowMethodModule（反射 hook Looper.mLogging）、SlowMethodConfig | 已完成 |
| apm-io | com.didi.apm.io | IoModule（主线程 IO 耗时）、IoConfig | 已完成 |
| apm-thread-monitor | com.didi.apm.threadmonitor | ThreadMonitorModule（线程膨胀/泄漏/死锁）、ThreadMonitorConfig | 已完成 |
| apm-battery | com.didi.apm.battery | BatteryModule（WakeLock/电量下降）、BatteryConfig | 已完成 |
| apm-sqlite | com.didi.apm.sqlite | SqliteModule（慢查询/主线程 DB）、SqliteConfig | 已完成 |
| apm-webview | com.didi.apm.webview | WebviewModule（页面加载/JS/白屏）、WebviewConfig | 已完成 |
| apm-ipc | com.didi.apm.ipc | IpcModule（Binder 调用耗时）、IpcConfig | 已完成 |

### 2.4 Demo 应用

| 模块 | 说明 | 状态 |
|------|------|------|
| apm-sample-app | 集成全部 14 个功能模块，含内存分配/崩溃/网络模拟按钮 | 已完成 |

---

## 三、项目统计

| 指标 | 数值 |
|------|------|
| Gradle 模块数 | 18 + root |
| Kotlin 源文件 | 60 |
| 测试类 | 18 |
| 测试方法 | 114 |
| 测试通过率 | 100% |
| JDK 版本 | 11 |
| AGP 版本 | 7.4.2 |
| compileSdk | 34 |
| minSdk | 21 |

### 构建命令

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew assembleDebug testDebugUnitTest
```

---

## 四、架构设计

### 4.1 设计原则

1. **先平台，后能力** — 统一框架做稳，每个专项复用基础设施
2. **先 Java 层可用，再 Native 层增强** — Memory 先做到稳定采样、预警、事件上报
3. **先离线闭环，再线上放量** — 本地可观测、日志可读、事件可验证，再接线上上传
4. **先主进程，后多进程** — 多进程支持有，第一阶段不追求全进程全功能
5. **先低风险能力，后高侵入能力** — Leak / OOM 预警优先于 Native Hook

### 4.2 两层架构

```
┌──────────────────────────────────────────────────────────┐
│                     Feature 层（14 模块）                  │
│  memory | crash | anr | launch | network | fps            │
│  slow_method | io | thread_monitor | battery              │
│  sqlite | webview | ipc                                    │
├──────────────────────────────────────────────────────────┤
│                     Core 层（4 模块）                       │
│  apm-model   — 统一事件模型 + line protocol 序列化          │
│  apm-core    — 初始化/注册/分发/限流/灰度/日志               │
│  apm-storage — 本地缓存（FileEventStore, ring buffer）      │
│  apm-uploader— 上传通道（重试/批量/指数退避）                │
└──────────────────────────────────────────────────────────┘
```

### 4.3 事件流

```
业务模块 → Apm.emit() → ApmDispatcher
  ├─ 限流检查（RateLimiter，ERROR/FATAL 跳过）
  ├─ 本地存储（FileEventStore.append）
  └─ 上传（RetryingApmUploader → delegate.upload）
```

### 4.4 模块生命周期

```kotlin
interface ApmModule {
    val name: String
    fun onInitialize(context: ApmContext)   // Apm.register() 时
    fun onStart()                           // Application.onCreate 后
    fun onStop()                            // 进程结束时
}
```

---

## 五、下一步规划

### 5.1 待开发

1. **内存分配频率（Memory Churn）** — 检测高频 GC 导致的卡顿
2. **渲染过度绘制（Overdraw）** — 检测 UI 层级过深、过度绘制

### 5.2 现有模块增强

1. **内存泄漏增强** — 支持 Root 引用链分析
2. **Hprof 裁剪实现** — 当前为占位，需实现真正裁剪
3. **Native Crash 增强** — 当前为占位，需实现 signal handler
4. **网络全链路** — 当前为回调模式，可集成 OkHttp Interceptor 自动采集
5. **启动监控增强** — 增加温启动检测，分阶段上报

---

## 附录 A：Memory 模块详细设计

> 对标字节 Memory Insight / 微信 Matrix / 快手 KOOM

### A.1 Memory 能力分层

| 层级 | 目标 | 功能 |
|------|------|------|
| L1 MVP | 可用、可灰度 | Java Heap/PSS/NativeHeap 采样 + 前后台调度 + 阈值告警 |
| L2 Advanced | Java 问题定位 | Activity/Fragment/ViewModel 泄漏 + OOM 预警 + Hprof Dump |
| L3 Expert | Native 问题定位 | fork 子进程 dump + Hprof Strip + Native Heap Hook |

### A.2 Memory 子模块

```
apm-memory/
  MemoryModule        — 模块入口，整合所有子组件
  MemoryConfig        — 全部配置项（采样间隔/阈值/开关）
  MemorySampler       — Java Heap/PSS/NativeHeap/VmRSS/GC 统计采集
  MemoryScheduler     — 前后台定时轮询（ProcessLifecycleOwner）
  MemorySnapshot      — 采集数据模型
  MemoryReporter      — 事件上报 + OOM 预警联动
  leak/
    ActivityLeakDetector  — WeakRef + ReferenceQueue + 延迟 GC 检测
    FragmentLeakDetector  — FragmentManager 生命周期回调
    ViewModelLeakDetector — 反射检测 Context/View 持有
    LeakResult            — 泄漏结果数据类
  oom/
    OomMonitor         — AtomicLong/AtomicBoolean 线程安全 + CAS 防重入
    HprofDumper        — dump 触发（占位，待实现 fork 方案）
    HprofStripProcessor — Hprof 裁剪（占位，待实现 byte[] 零填充）
  nativeheap/
    NativeHeapMonitor  — Debug.getNativeHeapAllocatedSize 统计
    NativeHeapStats    — Native 堆统计数据类
```

### A.3 关键踩坑记录

| 坑点 | 解决方案 |
|------|----------|
| PSS 采集耗时 5~20ms | 子线程采集，前台 5s 间隔 |
| fork dump 在 Android P 以下有风险 | 版本判断，降级为直接 dump |
| Hprof 文件 100~300MB | Strip 裁掉 primitive array，压缩 60~80% |
| GC 触发影响用户 | 仅在 Leak 检测 IdleHandler 中调用，有频率限制 |
| Native hook 导致 SDK 崩溃 | 排除 /system/ 下的 so，只 hook app native 代码 |
| 子进程 dump 后忘记回收 | dump 后立刻 kill(getpid(), SIGKILL) |

### A.4 性能预算

| 模块 | CPU 开销 | 内存开销 |
|------|----------|----------|
| 水位采集 (5s 间隔) | < 0.1% | ~200KB |
| PSS 采集 | ~1ms CPU 脉冲 | 可忽略 |
| Leak 检测 | < 0.5% | ~500KB |
| Hprof Dump（触发时） | STW 1~5s | 临时 200~300MB |

---

## 附录 B：编码规范（CLAUDE.md 强制）

1. **规则一**：所有 public/internal/private 成员变量和方法必须添加 KDoc 注释
2. **规则二**：方法内分支/循环/异常/回调等关键节点必须添加行内注释
3. **规则三**：所有常量提取为 `private const val` 或 companion object 命名常量，禁止裸数字/裸字符串

---

## 附录 C：开源参考

| 项目 | 学习重点 |
|------|----------|
| 微信 Matrix | ResourceCanary, Hprof 分析, TraceCanary |
| 快手 KOOM | fork dump 完整实现, Hprof Stripper |
| 字节 bhook | Native PLT hook |
| LeakCanary/Shark | Hprof 解析、引用链分析 |
| Perfetto | 系统级 trace |
