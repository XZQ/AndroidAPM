# AndroidAPM 架构文档索引

> 同步日期：2026-07-10。当前源码与测试优先于历史文档。

## 推荐阅读顺序

1. [整体架构](00_整体架构.md)
2. [apm-core](01_apm-core.md)
3. [model / storage / uploader](02_apm-model-storage-uploader.md)
4. 当前任务对应的监控或扩展模块

## 基础架构

| 文档 | 内容 |
|---|---|
| [00_整体架构](00_整体架构.md) | 产品边界、模块依赖、事件管线、生命周期、线程与交付语义 |
| [01_apm-core](01_apm-core.md) | 初始化、模块生命周期、dispatcher、多进程、自监控、降级 |
| [02_model-storage-uploader](02_apm-model-storage-uploader.md) | 事件模型、codec、SQLite outbox、HTTP、重试和确认 |

## 15 个监控模块

| # | 模块文档 | 主要信号 | 接入类型 |
|---:|---|---|---|
| 1 | [Memory](03_apm-memory.md) | Heap/PSS、泄漏、OOM、Hprof | 自动采样 + 部分手动 API |
| 2 | [Crash](04_apm-crash.md) | Java/Native crash、退出原因 | 自动；Native opt-in |
| 3 | [ANR](05_apm-anr.md) | SIGQUIT、Watchdog、分类 | 自动 + 降级 |
| 4 | [Launch](06_apm-launch.md) | 冷/热/温、首帧、阶段 | Lifecycle + 阶段 callback |
| 5 | [Network](07_apm-network.md) | OkHttp 全阶段、慢/错请求 | Interceptor/Listener/手动 |
| 6 | [FPS](08_apm-fps.md) | VSync、FrameMetrics、掉帧 | Lifecycle 自动 |
| 7 | [Slow Method](09_apm-slow-method.md) | Looper、采样、ASM | runtime + Gradle plugin |
| 8 | [IO](10_apm-io.md) | 流、FD、Closeable、PLT Hook | wrapper + optional native |
| 9 | [Battery](11_apm-battery.md) | 电量、CPU、WakeLock/GPS/Alarm | 自动 + host callback |
| 10 | [SQLite](12_apm-sqlite.md) | 慢 SQL、主线程、QueryPlan | wrapper/手动 |
| 11 | [WebView](13_apm-webview.md) | 页面、JS、白屏、资源 | host callback |
| 12 | [IPC](14_apm-ipc.md) | Binder 调用耗时 | host callback |
| 13 | [Thread](15_apm-thread-monitor.md) | 数量、同名、BLOCKED | 定时采样 |
| 14 | [GC](16_apm-gc-monitor.md) | 次数、耗时、Heap、分配 | 定时采样 |
| 15 | [Render](17_apm-render.md) | View 数量/层级 | Lifecycle 自动 |

## 扩展模块

| 文档 | 内容 |
|---|---|
| [Trace](18_apm-trace.md) | 手动 Span/Trace API |
| [OTel exporter](19_apm-otel-exporter.md) | OTel-compatible 数据映射，不负责网络发送 |

## 生成图谱

`generated-diagrams/` 包含五组同步维护的 SVG 和 PNG：

- `android-apm-overview`
- `android-apm-event-pipeline`
- `android-apm-module-dependencies`
- `android-apm-monitoring-modules`
- `android-apm-slow-method-instrumentation`

SVG 是可维护源；PNG 是分发预览。修改架构拓扑后必须同时刷新两种格式。

## 历史与报告产物

- `../APM_Review_2026-07-08.md`：历史评审及当前处置状态
- `../APM_Optimization_2026-07-08.md`：历史优化建议及当前落地状态
- `../APM_对比报告.docx`、`../APM_框架对比报告.docx`：可分发报告
- `../记录.zip`、`../绘制.jpeg`：历史参考资料，不作为当前代码证明

## 文档规则

- 代码事实优先。
- 明确区分自动采集与宿主接线。
- 明确区分实现存在与默认开启。
- 明确区分客户端 SDK 与外部后台。
- 不使用“exactly-once”描述当前上传；当前是 acknowledged at-least-once。
- 不把 `.github/` 描述为跟踪中的云端 CI；该目录按项目策略忽略。
