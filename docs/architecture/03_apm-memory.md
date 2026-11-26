# apm-memory 模块

> 同步日期：2026-11-26｜模块名：`memory`

## 目的与入口

`MemoryModule` 组合内存采样、泄漏检测、OOM 预警、Hprof 产物和 Native Heap 采样。注册后通过 ProcessLifecycleOwner 和 ActivityLifecycleCallbacks 自动运行；`captureOnce(reason)` 和 `checkViewModel(viewModel)` 是公开手动入口。

## 采集组件

- `MemorySampler`：Java heap、PSS、native heap、VmRSS、GC 等快照
- `MemorySampleScheduler`：前后台不同周期
- `MemoryReporter`：snapshot/alert/leak 事件
- `ActivityLeakDetector`：destroy 后 WeakReference + delayed GC/check
- `FragmentLeakDetector`：Fragment 生命周期
- `ViewModelLeakDetector`：检查非 `Application` 的 `Context` 或 `View` 持有（排除 `AndroidViewModel` 进程级 `Application` 引用）
- `OomMonitor`：heap/PSS/native/system trim 阈值
- `HprofDumper`：直接或可选 fork dump、可选 strip，模块启动与每次成功 dump 后清理，raw/strip 合计最多保留 5 个文件；当前报告文件优先保留，独占临时路径避免同毫秒覆盖
- `HprofStripProcessor`：以 8 KiB 缓冲流式解析 4/8 字节 ID 与 ART heap/root/class/array 布局，保留结构并等长清零 primitive payload；不缩减文件大小。非法 header/record/type、截断或取消返回失败并删除部分输出
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

周期采样和 leak check 使用 `ApmExecutors`/HandlerThread；Hprof dump 使用后台 executor。Hprof 是大文件操作，默认关闭并有 cooldown/文件数限制。模块关闭会丢弃排队 dump，永久关闭最终文件/事件交接；无法中断的 ART dump 返回后删除迟到产物，旧上下文不进入新 runtime。fork dump 依赖设备/ART 兼容性，失败回退直接 dump。

## 降级与边界

- 高风险 Hprof/Native 能力必须显式开启。
- ViewModel 泄漏不会自动遍历所有实例，宿主需调用 `checkViewModel`。
- Hprof 引用链分析不在端上内建（原未接线的 `ReferenceChainAnalyzer` 已在 `0.2.0` 移除）：轻量检测仅提供 bounded `suspectFields`，`referenceChain` 恒为空；需要 GC Root 证明时集成 Shark/LeakCanary，不宣称本地实现与其等价。
- fork dump/Native Heap 需要真机矩阵验证。
- dump 产物如何上传、脱敏、留存由集成方定义。

## 测试

配置默认值、真实 Android/Robolectric 采样快照、Reporter 快照/告警/泄漏分类、ViewModel Context/View 引用、OOM warn/critical/system/native 边界、Hprof primitive array 清零与截断输入清理、采样调度器启动/重调度/停止与停止后拒绝、NativeHeapMonitor enable/disable 门控、LeakCheckQueue retained 交付的 suspectFields/上限/异常 sink 隔离，以及 JNI dump 契约由 module/JVM 测试覆盖。报告逻辑通过内部 sink 验证字段和优先级，不依赖全局 `Apm` 状态；真机 ART dump 仍需设备验证。

## 时间语义

采样和文件产物 timestamp 保持 Unix epoch；Hprof 分析 duration 与 OOM 冷却窗口使用 `ApmClock` 单调时间，避免系统时间回拨产生负耗时或绕过冷却。

## 2026-09-12 生命周期泄漏检查

Activity/Fragment 泄漏检查改为模块会话共享的一个后台队列，最多 128 个弱引用观察项、一个待执行回调，同批只请求一次 GC，GC 请求至少间隔 5 秒。目标 class/scene 在销毁时复制，队列和延迟闭包不强持有 Activity/Fragment；Fragment 仅 onFragmentDestroyed 触发检查，View 销毁与返回栈正常保留不触发。unregister/shutdown 清空所属观察项，GC 期间取消也禁止迟到结果。轻量反射仅输出 bounded suspectFields，referenceChain 留空；它是疑似保留检测，不是 GC Root 证明，也不覆盖独立 View 泄漏。后台 GC 仍可能暂停应用线程，真机开销需实际验收。 本项 memory 7 suites / 33 tests 和 apiCheck 通过；lint 0 errors，保留 MemorySampler 的 2 条既有 ObsoleteSdkInt 警告；文档检查通过。
