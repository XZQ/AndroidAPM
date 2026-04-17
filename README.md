# Android APM Framework

> 高性能 Android 应用性能监控框架，对标微信 Matrix + 快手 KOOM + Google 最佳实践

## 快速开始

```bash
# 构建
./gradlew assembleDebug

# 测试
./gradlew testDebugUnitTest
```

**环境要求**：JDK 11 / AGP 7.4.2 / Kotlin 1.8.10 / compileSdk 34 / minSdk 21

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  Feature 层（16 监控模块）                                │
│  memory|crash|anr|launch|network|fps|slow-method|io      │
│  thread-monitor|battery|sqlite|webview|ipc|gc-monitor   │
│  render                                                  │
├─────────────────────────────────────────────────────────┤
│  Core 层（4 基础模块）                                    │
│  model(事件模型) | core(分发/限流/灰度)                    │
│  storage(本地存储) | uploader(上传通道)                    │
├─────────────────────────────────────────────────────────┤
│  Tool 层                                                 │
│  apm-plugin(Gradle ASM 字节码插桩) | apm-sample-app      │
└─────────────────────────────────────────────────────────┘
```

## 监控能力

| 维度 | 核心能力 |
|------|---------|
| 内存 | Heap/PSS 采样 + Activity/Fragment/ViewModel 泄漏 + OOM 预警 + Hprof Dump |
| 崩溃 | Java UncaughtExceptionHandler + Native 信号解析 |
| ANR | **SIGQUIT 信号** + Watchdog 双重检测 + traces.txt 解析 + 原因分类 |
| 启动 | 6 阶段冷启动 + 热启动 + Choreographer 首帧检测 |
| 网络 | OkHttp Interceptor + EventListener + 聚合统计 |
| FPS | Choreographer VSync + 掉帧/卡顿/冻结分级 |
| 慢方法 | 反射 Hook + **ASM 字节码插桩** + 热点方法统计 |
| IO | **Native PLT Hook** + FD 泄漏 + 吞吐量 + Closeable 泄漏 |
| 电量 | WakeLock + 电量速率 + CPU Jiffies |
| SQLite | 慢查询 + 主线程 DB + QueryPlan 分析 |
| WebView | 页面加载 + JS 执行 + 白屏检测 |
| IPC | Binder 调用耗时 + 主线程阈值分级 |
| 线程 | 线程膨胀 + 同名泄漏 + 死锁检测 |
| GC | GC 频次/耗时/Heap 增长检测 |
| 渲染 | View 树深度/数量检测 |

## 集成示例

```kotlin
// Application.onCreate
Apm.init(this)                          // 初始化框架
Apm.register(MemoryModule())            // 注册需要的模块
Apm.register(CrashModule())
Apm.register(AnrModule())
// ... 注册其他模块
Apm.start()                             // 启动监控
```

## 模块列表

```
apm-model/          统一事件模型 + Line Protocol 序列化
apm-core/           初始化/注册/分发/限流(令牌桶)/灰度发布
apm-storage/        本地存储 (FileEventStore, ring buffer)
apm-uploader/       上传通道 (重试/批量/指数退避)
apm-memory/         内存监控 (水位/泄漏/OOM/Native Heap)
apm-crash/          崩溃监控 (Java + Native)
apm-anr/            ANR 监控 (SIGQUIT + Watchdog + traces.txt)
apm-launch/         启动监控 (冷/热/温 + 6 阶段)
apm-network/        网络监控 (OkHttp 全链路)
apm-fps/            FPS 监控 (Choreographer VSync)
apm-slow-method/    慢方法 (反射 Hook + ASM 插桩)
apm-io/             IO 监控 (Native PLT Hook + FD 泄漏)
apm-battery/        电量监控 (WakeLock + CPU)
apm-sqlite/         SQLite 监控 (慢查询 + QueryPlan)
apm-webview/        WebView 监控 (加载/JS/白屏)
apm-ipc/            IPC 监控 (Binder 耗时)
apm-thread-monitor/ 线程监控 (膨胀/泄漏/死锁)
apm-gc-monitor/     GC 监控 (频次/耗时)
apm-render/         渲染监控 (View 树分析)
apm-plugin/         Gradle 插件 (ASM 字节码插桩)
apm-sample-app/     示例应用
```

## 详细文档

完整项目文档见 [docs/Android_APM_项目文档.md](docs/Android_APM_项目文档.md)，包含：
- 功能对比矩阵（vs Matrix/KOOM/Google）
- 模块详细设计
- 关键模块架构图
- 测试覆盖情况
- 待完善项清单

## 编码规范

1. 所有成员变量和方法必须添加 KDoc 注释
2. 方法内分支/循环/异常/回调必须添加行内注释
3. 所有常量提取为命名常量，禁止裸数字/裸字符串

## 开源参考

微信 Matrix | 快手 KOOM | 字节 bhook | LeakCanary/Shark | Perfetto
