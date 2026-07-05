# APM 架构文档索引

> Android APM 框架完整架构文档，覆盖所有 20 个模块

---

## 文档清单

| 文件 | 内容 |
|------|------|
| [../PROJECT_HANDOFF.md](../PROJECT_HANDOFF.md) | 当前项目交接快照、验证基线、真实未完成项、新电脑接手步骤 |
| [00_整体架构.md](00_整体架构.md) | 系统全景架构、模块依赖图、事件流程、线程模型、数据模型、限流灰度架构 |
| [01_apm-core.md](01_apm-core.md) | 核心框架层：Apm入口、ApmModule接口、ApmDispatcher分发器、RateLimiter限流、灰度控制 |
| [02_apm-model-storage-uploader.md](02_apm-model-storage-uploader.md) | 数据模型、SQLite 持久化 outbox、FileStore、批量/Gzip/重试上传 |
| [03_apm-memory.md](03_apm-memory.md) | 内存监控：Heap/PSS采样、Activity/Fragment/ViewModel泄漏、OOM预警、HprofDump/Strip、NativeHeap |
| [04_apm-crash.md](04_apm-crash.md) | 崩溃监控：Java UncaughtExceptionHandler、Native信号解析、Tombstone扫描 |
| [05_apm-anr.md](05_apm-anr.md) | ANR监控：Watchdog 默认检测、可选 SIGQUIT 回调、traces.txt、原因分类、堆栈采样、去重 |
| [06_apm-launch.md](06_apm-launch.md) | 启动监控：6阶段冷启动、热启动/温启动、Choreographer首帧检测、瓶颈分析 |
| [07_apm-network.md](07_apm-network.md) | 网络监控：OkHttp Interceptor+EventListener、DNS/TCP/TLS全链路、聚合统计 |
| [08_apm-fps.md](08_apm-fps.md) | FPS监控：Choreographer VSync、Window FrameMetrics、掉帧/卡顿/冻结分级 |
| [09_apm-slow-method.md](09_apm-slow-method.md) | 慢方法检测：反射Hook Looper.mLogging、ASM字节码插桩、栈采样、热点方法统计 |
| [10_apm-io.md](10_apm-io.md) | IO监控：Native PLT Hook(双层架构)、FD泄漏(/proc/self/fd)、吞吐量统计、Closeable泄漏 |
| [11_apm-battery.md](11_apm-battery.md) | 电量监控：电量/CPU 与宿主回调接入的 WakeLock/GPS/Alarm |
| [12_apm-sqlite.md](12_apm-sqlite.md) | SQLite监控：慢查询、主线程DB、大数据量操作、QueryPlan分析(全表扫描/临时BTree/自动索引) |
| [13_apm-webview.md](13_apm-webview.md) | WebView监控：页面加载、JS、白屏、并发资源瀑布 |
| [14_apm-ipc.md](14_apm-ipc.md) | IPC/Binder监控：Binder调用耗时、主线程阈值分级、聚合统计 |
| [15_apm-thread-monitor.md](15_apm-thread-monitor.md) | 线程监控：线程数膨胀、同名泄漏、BLOCKED死锁检测 |
| [16_apm-gc-monitor.md](16_apm-gc-monitor.md) | GC监控：GC频次飙升、GC耗时占比、Heap增长、分配频率、GC回收率 |
| [17_apm-render.md](17_apm-render.md) | 渲染监控：View树数量检测、层级深度检测；过度绘制列入Roadmap |
| [18_apm-trace.md](18_apm-trace.md) | 手动 Trace/Span API |
| [19_apm-otel-exporter.md](19_apm-otel-exporter.md) | OpenTelemetry 语义映射适配层（不直接发送） |

## 生成图谱

| 文件 | 内容 |
|------|------|
| [generated-diagrams/android-apm-overview.svg](generated-diagrams/android-apm-overview.svg) / [PNG](generated-diagrams/android-apm-overview.png) | 全局分层架构：宿主接入、15 个监控模块、Core 管线、扩展和构建工具 |
| [generated-diagrams/android-apm-event-pipeline.svg](generated-diagrams/android-apm-event-pipeline.svg) / [PNG](generated-diagrams/android-apm-event-pipeline.png) | 运行时事件流：Apm.emit、ApmEvent、聚合、限流、脱敏、存储、上传 |
| [generated-diagrams/android-apm-module-dependencies.svg](generated-diagrams/android-apm-module-dependencies.svg) / [PNG](generated-diagrams/android-apm-module-dependencies.png) | Gradle 模块依赖：Sample、监控模块、基础模块、扩展模块、included build |
| [generated-diagrams/android-apm-monitoring-modules.svg](generated-diagrams/android-apm-monitoring-modules.svg) / [PNG](generated-diagrams/android-apm-monitoring-modules.png) | 15 个监控模块能力分组图 |
| [generated-diagrams/android-apm-slow-method-instrumentation.svg](generated-diagrams/android-apm-slow-method-instrumentation.svg) / [PNG](generated-diagrams/android-apm-slow-method-instrumentation.png) | slow-method 编译期 ASM 插桩与运行时上报链路 |

## 图表类型

每个模块文档包含：

- **架构图** — 模块内部组件关系和分层
- **类图** — 核心类、属性、方法、依赖关系
- **流程图** — 关键业务流程（检测、上报、降级）
- **检测维度** — 每种异常的检测逻辑和阈值

## 如何阅读

1. 先读 `../PROJECT_HANDOFF.md` 确认当前进度、未完成项和验证基线
2. 再读 `00_整体架构.md` 了解全局
3. 按 `01→19` 顺序阅读各模块
4. 每个模块文档独立完整，可单独阅读
