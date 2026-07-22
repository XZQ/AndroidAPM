# apm-render 模块

> 同步日期：2026-07-11｜模块名：`render`

## 目的与入口

`RenderModule` 实现 ActivityLifecycleCallbacks，在 Activity created 后通过 decorView post 遍历 View 树，统计总 View 数和最大层级深度。

## 已实现检测

- view count > 300 -> `view_count_spike`
- max depth > 10 -> `deep_hierarchy`
- 带 Activity scene、count/depth；公共层级/帧指标不提供业务调用栈
- Activity visible 期间挂载 API 24+ `Window.OnFrameMetricsAvailableListener`
- 每 60 帧输出 count/slow/average/max/platform dropped callback；默认 slow frame 32ms

遍历递归访问 `ViewGroup` children，结果封装为 `RenderStats`。

## 默认配置

| 配置 | 默认 | 当前消费状态 |
|---|---:|---|
| monitor | 开 | 已消费 |
| view depth | 10 | 已消费 |
| view count | 300 | 已消费 |
| `viewDrawThresholdMs` | 16ms / deprecated | 公共 API 不提供单 View draw timing |
| `detectOverdraw` | false / deprecated | 公共 API 不支持 GPU overdraw 计数 |
| stack max | 4000 / deprecated | 层级与 FrameMetrics 不采集调用栈 |
| `slowFrameThresholdMs` | 32ms | FrameMetrics 已消费 |

## 线程与开销

View 树只能在主线程安全访问，因此遍历在主线程 post callback 中执行；FrameMetrics listener 使用主线程 Handler 做常数级 accumulator 更新，每 60 帧才发一个事件。超大 View 树和监听开销需真机 benchmark。

## 边界

- 支持整帧 total duration，不宣称单 View.draw 耗时。
- 公共 API 不提供 GPU overdraw/pixel 计数，因此不实现反射/开发者选项模拟。
- 只在 Activity created 后执行一次，不持续追踪动态 View 树变化。
- Compose UI 不等价于传统 View hierarchy，当前文档不宣称 Compose tree 分析。

## 测试

Config、RenderStats、FrameMetrics fixed-window accumulator 和阈值事件有测试；真实复杂 View/Compose/主线程开销由 `apm-benchmark` 与设备矩阵验证。

## 时间语义

RenderStats 的对外采样 timestamp 明确为 Unix epoch，便于 collector 排序；FrameMetrics 自身提供 duration，不用 epoch 差值推导渲染耗时。
