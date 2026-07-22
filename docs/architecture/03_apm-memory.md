# apm-memory 模块

> 同步日期：2026-07-16｜模块名：`memory`

## 目的与入口

`MemoryModule` 组合内存采样、泄漏检测、OOM 预警、Hprof 产物和 Native Heap 采样。注册后通过 ProcessLifecycleOwner 和 ActivityLifecycleCallbacks 自动运行；`captureOnce(reason)` 和 `checkViewModel(viewModel)` 是公开手动入口。

## 采集组件

- `MemorySampler`：Java heap、PSS、native heap、VmRSS、GC 等快照
- `MemorySampleScheduler`：前后台不同周期
- `MemoryReporter`：snapshot/alert/leak 事件
- `ActivityLeakDetector`：destroy 后 WeakReference + delayed GC/check
- `FragmentLeakDetector`：Fragment 生命周期
- `ViewModelLeakDetector`：检查 Context/View 持有
- `OomMonitor`：heap/PSS/native/system trim 阈值
- `HprofDumper`：直接或可选 fork dump、可选 strip、最多保留 5 个文件
- `HprofStripProcessor`：二进制 Hprof primitive array 清理；截断 header/record 返回失败并删除部分输出
- `ReferenceChainAnalyzer`：Hprof 索引与 BFS 引用路径
- `NativeHeapMonitor`：`Debug.getNativeHeapAllocatedSize`

## 主要默认值

| 配置 | 默认 |
|---|---:|
| foreground/background sample | 15s / 60s |
| Java heap warn/critical | 0.80 / 0.90 |
| total PSS warn | 300 MiB |
| sample rate | 1.0 |
| Activity/Fragment/ViewModel leak | 开 |
| leak check delay | 5s |
| OOM monitor | 开 |
| Hprof dump / fork / strip | 关 / 关 / 关 |
| dump cooldown | 10min |
| Native monitor | 关 |
| native heap warn | 512 MiB |

## 事件

`memory_snapshot`, `memory_alert`, `memory_leak`, `oom_warn`, `oom_critical`, `system_low_memory`, `system_mem_warn`, `native_heap_warn`, `native_heap_stats`, `hprof_dump`。

## 线程与资源

周期采样和 leak check 使用 `ApmExecutors`/HandlerThread；Hprof dump 使用后台 executor。Hprof 是大文件操作，默认关闭并有 cooldown/文件数限制。fork dump 依赖设备/ART 兼容性，失败回退直接 dump。

## 降级与边界

- 高风险 Hprof/Native 能力必须显式开启。
- ViewModel 泄漏不会自动遍历所有实例，宿主需调用 `checkViewModel`。
- Hprof 引用链分析是本地实现，不等同于 Shark 全特性/全版本兼容。
- fork dump/Native Heap 需要真机矩阵验证。
- dump 产物如何上传、脱敏、留存由集成方定义。

## 测试

配置默认值、真实 Android/Robolectric 采样快照、Reporter 快照/告警/泄漏分类、ViewModel Context/View 引用、OOM warn/critical/system/native 边界、Hprof primitive array 清零与截断输入清理，以及 JNI dump 契约由 module/JVM 测试覆盖。报告逻辑通过内部 sink 验证字段和优先级，不依赖全局 `Apm` 状态；真机 ART dump 仍需设备验证。

## 时间语义

采样和文件产物 timestamp 保持 Unix epoch；Hprof 分析 duration 与 OOM 冷却窗口使用 `ApmClock` 单调时间，避免系统时间回拨产生负耗时或绕过冷却。
