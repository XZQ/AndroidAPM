# APM 架构文档索引

> Android APM 框架完整架构文档，覆盖所有 20 个模块

---

## 文档清单

| 文件 | 内容 |
|------|------|
| [00_整体架构.md](00_整体架构.md) | 系统全景架构、模块依赖图、事件流程、线程模型、数据模型、限流灰度架构 |
| [01_apm-core.md](01_apm-core.md) | 核心框架层：Apm入口、ApmModule接口、ApmDispatcher分发器、RateLimiter限流、灰度控制 |
| [02_apm-model-storage-uploader.md](02_apm-model-storage-uploader.md) | 数据模型(ApmEvent/LineProtocol)、本地存储(FileEventStore/RingBuffer)、上传通道(重试/批量/退避) |
| [03_apm-memory.md](03_apm-memory.md) | 内存监控：Heap/PSS采样、Activity/Fragment/ViewModel泄漏、OOM预警、HprofDump/Strip、NativeHeap |
| [04_apm-crash.md](04_apm-crash.md) | 崩溃监控：Java UncaughtExceptionHandler、Native信号解析、Tombstone扫描 |
| [05_apm-anr.md](05_apm-anr.md) | ANR监控：SIGQUIT信号+Watchdog双重检测、traces.txt解析、原因分类(5类)、堆栈采样、去重 |
| [06_apm-launch.md](06_apm-launch.md) | 启动监控：6阶段冷启动、热启动/温启动、Choreographer首帧检测、瓶颈分析 |
| [07_apm-network.md](07_apm-network.md) | 网络监控：OkHttp Interceptor+EventListener、DNS/TCP/TLS全链路、聚合统计 |
| [08_apm-fps.md](08_apm-fps.md) | FPS监控：Choreographer VSync、Window FrameMetrics、掉帧/卡顿/冻结分级 |
| [09_apm-slow-method.md](09_apm-slow-method.md) | 慢方法检测：反射Hook Looper.mLogging、ASM字节码插桩、栈采样、热点方法统计 |
| [10_apm-io.md](10_apm-io.md) | IO监控：Native PLT Hook(双层架构)、FD泄漏(/proc/self/fd)、吞吐量统计、Closeable泄漏 |
| [11_apm-battery.md](11_apm-battery.md) | 电量监控：WakeLock追踪、电量下降速率、CPU Jiffies采样(/proc/self/stat)、Alarm泛洪 |
| [12_apm-sqlite.md](12_apm-sqlite.md) | SQLite监控：慢查询、主线程DB、大数据量操作、QueryPlan分析(全表扫描/临时BTree/自动索引) |
| [13_apm-webview.md](13_apm-webview.md) | WebView监控：页面加载耗时、JS执行耗时、白屏检测 |
| [14_apm-ipc.md](14_apm-ipc.md) | IPC/Binder监控：Binder调用耗时、主线程阈值分级、聚合统计 |
| [15_apm-thread-monitor.md](15_apm-thread-monitor.md) | 线程监控：线程数膨胀、同名泄漏、BLOCKED死锁检测 |
| [16_apm-gc-monitor.md](16_apm-gc-monitor.md) | GC监控：GC频次飙升、GC耗时占比、Heap增长、分配频率、GC回收率 |
| [17_apm-render.md](17_apm-render.md) | 渲染监控：View树数量检测、层级深度检测、过度绘制(预留) |

## 图表类型

每个模块文档包含：

- **架构图** — 模块内部组件关系和分层
- **类图** — 核心类、属性、方法、依赖关系
- **流程图** — 关键业务流程（检测、上报、降级）
- **检测维度** — 每种异常的检测逻辑和阈值

## 如何阅读

1. 先读 `00_整体架构.md` 了解全局
2. 按 `01→17` 顺序阅读各模块
3. 每个模块文档独立完整，可单独阅读
