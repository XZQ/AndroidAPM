<p align="center">
  <h1 align="center">Android APM Framework</h1>
  <p align="center">
    <b>高性能 Android 应用性能监控框架</b><br/>
    对标微信 Matrix + 快手 KOOM + Google 最佳实践
  </p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform"/>
  <img src="https://img.shields.io/badge/API-21%2B-brightgreen.svg" alt="API"/>
  <img src="https://img.shields.io/badge/Kotlin-1.8.10-blue.svg" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/AGP-7.4.2-orange.svg" alt="AGP"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"/>
</p>

---

## 简介

Android APM Framework 是一个全维度 Android 性能监控框架，覆盖 **16 个监控维度**，从内存泄漏到 ANR，从启动耗时到网络链路，从帧率卡顿到 IO 异常——一站式解决应用性能监控需求。

核心设计理念：

- **模块化架构** — 每个 APM 维度独立模块，按需集成，零耦合
- **低侵入接入** — 一行 `Apm.init()` + 注册所需模块，无需修改业务代码
- **高性能采集** — 令牌桶限流 + 灰度发布 + 动态配置，生产环境可用
- **对标业界** — 借鉴微信 Matrix、快手 KOOM、Google 最佳实践

## 特性

### 全方位 16 维监控

| # | 维度 | 模块 | 核心能力 |
|---|------|------|---------|
| 1 | [内存](docs/architecture/03_apm-memory.md) | apm-memory | Heap/PSS 采样、Activity/Fragment/ViewModel 泄漏检测、OOM 预警、Hprof Dump & Strip、NativeHeap 监控 |
| 2 | [崩溃](docs/architecture/04_apm-crash.md) | apm-crash | Java UncaughtExceptionHandler、Native 信号解析(SIGSEGV/SIGABRT)、Tombstone 扫描 |
| 3 | [ANR](docs/architecture/05_apm-anr.md) | apm-anr | SIGQUIT 信号 + Watchdog 双重检测、traces.txt 解析、5 类原因分类、堆栈去重 |
| 4 | [启动](docs/architecture/06_apm-launch.md) | apm-launch | 6 阶段冷启动追踪、热启动/温启动、Choreographer 首帧检测、瓶颈分析 |
| 5 | [网络](docs/architecture/07_apm-network.md) | apm-network | OkHttp Interceptor + EventListener、DNS→TCP→TLS→Headers→Body 全链路耗时、聚合统计 |
| 6 | [FPS](docs/architecture/08_apm-fps.md) | apm-fps | Choreographer VSync + FrameMetrics 双引擎、掉帧/卡顿/冻结三级分级 |
| 7 | [慢方法](docs/architecture/09_apm-slow-method.md) | apm-slow-method | 反射 Hook Looper.mLogging + ASM 字节码插桩双引擎、栈采样、热点方法统计 |
| 8 | [IO](docs/architecture/10_apm-io.md) | apm-io | Native PLT Hook 双层架构、FD 泄漏(/proc/self/fd)、吞吐量统计、Closeable 泄漏(PhantomReference) |
| 9 | [电量](docs/architecture/11_apm-battery.md) | apm-battery | WakeLock 追踪、电量下降速率、CPU Jiffies 采样(/proc/self/stat)、Alarm 泛洪检测 |
| 10 | [SQLite](docs/architecture/12_apm-sqlite.md) | apm-sqlite | 慢查询检测、主线程 DB 操作、大数据量操作、QueryPlan 分析(全表扫描/临时BTree) |
| 11 | [WebView](docs/architecture/13_apm-webview.md) | apm-webview | 页面加载耗时、JS 执行耗时、白屏检测 |
| 12 | [IPC](docs/architecture/14_apm-ipc.md) | apm-ipc | Binder 调用耗时监控、主线程阈值分级、聚合统计 |
| 13 | [线程](docs/architecture/15_apm-thread-monitor.md) | apm-thread-monitor | 线程数膨胀、同名泄漏、BLOCKED 死锁检测 |
| 14 | [GC](docs/architecture/16_apm-gc-monitor.md) | apm-gc-monitor | GC 频次飙升、GC 耗时占比、Heap 增长、分配频率、GC 回收率 |
| 15 | [渲染](docs/architecture/17_apm-render.md) | apm-render | View 树数量检测、层级深度检测、过度绘制(预留) |

### 核心能力

- **令牌桶限流** — RateLimiter 支持 ERROR/FATAL 级别跳过限流，保护上报通道
- **灰度发布** — GrayReleaseController 支持按比例开启新模块
- **动态配置** — DynamicConfigProvider 运行时调整阈值，无需发版
- **本地存储** — FileEventStore + RingBuffer，防数据丢失
- **重试上传** — RetryingApmUploader 批量 + 指数退避，网络异常自动重试
- **ASM 插桩** — Gradle Transform API 字节码级方法耗时采集
- **Native Hook** — PLT Hook (xhook/bhook) 实现 IO 拦截
- **Hprof 裁剪** — 二进制解析 + 原始数组剥离，大幅缩小 dump 文件

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Application Layer                        │
│                      (Your App)                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│   │ Memory   │ │ Crash    │ │ ANR      │ │ Launch   │       │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│   │ Network  │ │ FPS      │ │ SlowMethod│ │ IO       │       │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │  Feature 层
│   │ Battery  │ │ SQLite   │ │ WebView  │ │ IPC      │       │  (16 模块)
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐                    │
│   │ Thread   │ │ GC       │ │ Render   │                    │
│   └──────────┘ └──────────┘ └──────────┘                    │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────┐  ┌──────────────┐  ┌──────────────┐       │  Core 层
│   │  apm-core   │  │  apm-model   │  │  apm-storage │       │  (4 基础模块)
│   │  分发/限流   │  │  事件模型     │  │  本地存储     │       │
│   │  灰度/日志   │  │  LineProtocol│  │  RingBuffer  │       │
│   └─────────────┘  └──────────────┘  └──────────────┘       │
│   ┌──────────────┐                                           │
│   │  apm-uploader│  重试/批量/退避                            │
│   └──────────────┘                                           │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│   ┌──────────────────┐  ┌──────────────────┐                │  Tool 层
│   │   apm-plugin     │  │  apm-sample-app  │                │
│   │  Gradle ASM 插桩  │  │   示例应用        │                │
│   └──────────────────┘  └──────────────────┘                │
└──────────────────────────────────────────────────────────────┘
```

## 快速开始

### 环境要求

- Android Studio Flamingo+
- JDK 11
- Kotlin 1.8.10
- AGP 7.4.2
- compileSdk 34 / minSdk 21

### 构建

```bash
# Debug 构建
./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 构建 + 测试
./gradlew assembleDebug testDebugUnitTest
```

### 集成

#### 1. 添加模块依赖

```kotlin
// settings.gradle.kts
include(":apm-core")
include(":apm-model")
include(":apm-storage")
include(":apm-uploader")
include(":apm-memory")    // 按需添加
include(":apm-crash")
include(":apm-anr")
include(":apm-launch")
// ... 其他所需模块
```

#### 2. 初始化

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Apm.init(this)                          // 初始化框架

        Apm.register(MemoryModule())            // 内存监控
        Apm.register(CrashModule())             // 崩溃监控
        Apm.register(AnrModule())               // ANR 监控
        Apm.register(LaunchModule())            // 启动监控
        Apm.register(NetworkModule())           // 网络监控
        Apm.register(FpsModule())               // FPS 监控
        Apm.register(SlowMethodModule())        // 慢方法检测
        Apm.register(IoModule())                // IO 监控
        // ... 按需注册其他模块

        Apm.start()                             // 启动监控
    }
}
```

#### 3. 自定义配置

```kotlin
// 单模块自定义配置
val memoryConfig = MemoryConfig(
    sampleIntervalMs = 2000,
    leakDetectEnabled = true,
    oomMonitorEnabled = true
)
Apm.register(MemoryModule(memoryConfig))

// 全局限流配置
val apmConfig = ApmConfig(
    rateLimitPerSecond = 10,
    enableGrayRelease = true,
    grayRatio = 0.1  // 10% 灰度
)
Apm.init(this, apmConfig)
```

#### 4. 自定义上报

```kotlin
// 实现 ApmUploader 接口
class MyUploader : ApmUploader {
    override fun upload(events: List<ApmEvent>): Boolean {
        // 上报至你的 APM 后台
        return myApiClient.send(events)
    }
}

// 注册
Apm.setUploader(MyUploader())
```

#### 5. 网络监控接入

```kotlin
// OkHttp 客户端添加监控
val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(NetworkModule.interceptor)
    .eventListenerFactory(NetworkModule.eventListenerFactory)
    .build()
```

## 模块一览

| 模块 | 包名 | 说明 |
|------|------|------|
| apm-model | 事件模型 | ApmEvent + Line Protocol 序列化 |
| apm-core | 核心框架 | 初始化/注册/分发/限流(令牌桶)/灰度/多进程/日志 |
| apm-storage | 本地存储 | EventStore 接口 + FileEventStore (RingBuffer) |
| apm-uploader | 上传通道 | HttpApmUploader + LogcatApmUploader + RetryingApmUploader |
| apm-memory | 内存监控 | 水位采样 + 泄漏检测 + OOM 预警 + Hprof Dump + fork dump + 引用链分析 |
| apm-crash | 崩溃监控 | Java + Native 信号处理器 + Tombstone |
| apm-anr | ANR 监控 | SIGQUIT + Watchdog + traces.txt 解析 |
| apm-launch | 启动监控 | 冷/热/温启动 + 6 阶段追踪 |
| apm-network | 网络监控 | OkHttp 全链路 (DNS→TCP→TLS→Body) |
| apm-fps | FPS 监控 | Choreographer VSync + FrameMetrics |
| apm-slow-method | 慢方法 | Looper Hook + ASM 字节码插桩 |
| apm-io | IO 监控 | Native PLT Hook + FD 泄漏 + Closeable 泄漏 + 零拷贝检测 |
| apm-battery | 电量监控 | WakeLock + CPU Jiffies + Alarm 泛洪 |
| apm-sqlite | SQLite 监控 | 慢查询 + QueryPlan 分析 |
| apm-webview | WebView 监控 | 页面加载 + JS 执行 + 白屏 + JS Bridge + 资源瀑布图 |
| apm-ipc | IPC 监控 | Binder 调用耗时 |
| apm-thread-monitor | 线程监控 | 膨胀/泄漏/死锁 |
| apm-gc-monitor | GC 监控 | 频次/耗时/Heap/分配率/回收率 |
| apm-render | 渲染监控 | View 树深度/数量 |
| apm-plugin | Gradle 插件 | ASM 字节码插桩 Transform |
| apm-sample-app | 示例应用 | 全模块集成 Demo |

## 与业界方案对比

| 能力 | Android APM | 微信 Matrix | 快手 KOOM |
|------|:-----------:|:-----------:|:---------:|
| 内存泄漏 (Activity/Fragment/ViewModel) | ✅ | ✅ | ✅ |
| OOM 预警 + Hprof Dump | ✅ | ✅ | ✅ |
| Hprof 裁剪 | ✅ | ✅ | ✅ |
| 引用链分析 (Hprof 解析 + BFS) | ✅ | ✅ | ✅ |
| fork 子进程 Dump (无 STW) | ✅ | ❌ | ✅ |
| NativeHeap 监控 | ✅ | ❌ | ✅ |
| Java 崩溃 | ✅ | ✅ | ❌ |
| Native 崩溃信号处理器 + Tombstone | ✅ | ✅ | ❌ |
| ANR 检测 (SIGQUIT + Watchdog) | ✅ | ✅ | ❌ |
| ANR 原因分类 | ✅ (5 类) | ❌ | ❌ |
| 冷启动 6 阶段 | ✅ | ✅ | ❌ |
| 热启动/温启动 | ✅ | ✅ | ❌ |
| 网络全链路 (DNS→Body) | ✅ | ❌ | ❌ |
| FPS 双引擎 | ✅ | ✅ | ❌ |
| 慢方法 Hook + ASM | ✅ | ✅ | ❌ |
| IO Native PLT Hook | ✅ | ✅ | ❌ |
| FD 泄漏检测 | ✅ | ✅ | ❌ |
| Closeable 泄漏 | ✅ | ❌ | ❌ |
| 零拷贝检测 | ✅ | ❌ | ❌ |
| SQLite QueryPlan 分析 | ✅ | ❌ | ❌ |
| Binder IPC 监控 | ✅ | ❌ | ❌ |
| 线程死锁检测 | ✅ | ❌ | ❌ |
| GC 监控 (5 维度) | ✅ | ❌ | ❌ |
| View 树分析 | ✅ | ❌ | ❌ |
| WakeLock + CPU Jiffies | ✅ | ❌ | ❌ |
| WebView 性能 + JS Bridge + 资源瀑布图 | ✅ | ❌ | ❌ |
| HTTP 上传通道 + Gzip 压缩 | ✅ | ❌ | ❌ |
| 多进程支持 (ContentProvider 自动初始化) | ✅ | ❌ | ❌ |
| 令牌桶限流 + 灰度发布 | ✅ | ❌ | ❌ |
| Gradle ASM 字节码插桩 | ✅ | ❌ | ❌ |
| 模块数量 | **16 监控 + 5 基础** | 6 插件 | 3 模块 |

## 项目结构

```
Android-APM/
├── apm-core/                  # 核心框架 (分发/限流/灰度)
│   └── throttle/              # 令牌桶限流 + 动态配置 + 灰度发布
├── apm-model/                 # 统一事件模型 + Line Protocol
├── apm-storage/               # 本地存储 (FileEventStore)
├── apm-uploader/              # 上传通道 (重试/批量/退避)
├── apm-memory/                # 内存监控
│   ├── leak/                  # Activity/Fragment/ViewModel 泄漏
│   ├── oom/                   # OOM 预警 + Hprof Dump/Strip
│   └── nativeheap/            # NativeHeap 监控
├── apm-crash/                 # 崩溃监控 (Java + Native)
├── apm-anr/                   # ANR 监控 (SIGQUIT + Watchdog)
├── apm-launch/                # 启动监控 (冷/热/温)
├── apm-network/               # 网络监控 (OkHttp)
├── apm-fps/                   # FPS 监控 (VSync + FrameMetrics)
├── apm-slow-method/           # 慢方法 (Hook + ASM)
├── apm-io/                    # IO 监控 (PLT Hook + FD)
├── apm-battery/               # 电量监控 (WakeLock + CPU)
├── apm-sqlite/                # SQLite 监控 (慢查询 + QueryPlan)
├── apm-webview/               # WebView 监控
├── apm-ipc/                   # IPC 监控 (Binder)
├── apm-thread-monitor/        # 线程监控
├── apm-gc-monitor/            # GC 监控
├── apm-render/                # 渲染监控 (View 树)
├── apm-plugin/                # Gradle 插件 (ASM)
├── apm-sample-app/            # 示例应用
├── docs/                      # 文档
│   ├── Android_APM_项目文档.md # 完整项目文档
│   └── architecture/          # 架构图 (18 个模块详细文档)
├── CLAUDE.md                  # 编码规范
├── build.gradle               # 根构建文件
└── settings.gradle            # 模块配置
```

## 文档

| 文档 | 说明 |
|------|------|
| [项目文档](docs/Android_APM_项目文档.md) | 完整项目文档：功能对比矩阵、模块设计、测试覆盖 |
| [整体架构](docs/architecture/00_整体架构.md) | 系统全景架构、模块依赖、事件流程、线程模型 |
| [模块架构](docs/architecture/) | 18 个文件的逐模块架构文档（类图/流程图/检测维度） |

## 开源参考

本项目借鉴了以下优秀开源项目的设计思路：

- [微信 Matrix](https://github.com/Tencent/matrix) — APM 插件化架构、IO/Hook 方案
- [快手 KOOM](https://github.com/KwaiAppTeam/KOOM) — 内存监控、Hprof 裁剪、NativeHeap
- [bytedance/bhook](https://github.com/nicepkg/bhook) — PLT Hook 实现
- [square/leakcanary](https://github.com/square/leakcanary) — 内存泄漏检测
- [android/perfetto](https://android.googlesource.com/platform/external/perfetto/) — 系统级性能分析

## 贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/your-feature`)
3. 提交更改 (`git commit -m 'Feat: your feature'`)
4. 推送分支 (`git push origin feature/your-feature`)
5. 创建 Pull Request

## License

```
Copyright 2024 Android APM Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
