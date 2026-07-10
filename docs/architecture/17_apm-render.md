# apm-render 模块

> 同步日期：2026-07-10｜模块名：`render`

## 目的与入口

`RenderModule` 实现 ActivityLifecycleCallbacks，在 Activity created 后通过 decorView post 遍历 View 树，统计总 View 数和最大层级深度。

## 已实现检测

- view count > 300 -> `view_count_spike`
- max depth > 10 -> `deep_hierarchy`
- 带 Activity scene、count/depth 和截断 stack

遍历递归访问 `ViewGroup` children，结果封装为 `RenderStats`。

## 默认配置

| 配置 | 默认 | 当前消费状态 |
|---|---:|---|
| monitor | 开 | 已消费 |
| view depth | 10 | 已消费 |
| view count | 300 | 已消费 |
| `viewDrawThresholdMs` | 16ms | 未消费 |
| `detectOverdraw` | true | 未消费 |
| stack max | 4000 | 已消费 |

## 线程与开销

View 树只能在主线程安全访问，因此遍历在主线程 post callback 中执行；事件后续异步分发。超大 View 树会增加单次遍历开销，需真机测量。

## 边界

- 当前没有 draw duration 采样。
- 当前没有 GPU overdraw/pixel 分析。
- 只在 Activity created 后执行一次，不持续追踪动态 View 树变化。
- Compose UI 不等价于传统 View hierarchy，当前文档不宣称 Compose tree 分析。

## 测试

Config、RenderStats 和阈值事件有测试；真实复杂 View/Compose/主线程开销需 instrumented 测试。
