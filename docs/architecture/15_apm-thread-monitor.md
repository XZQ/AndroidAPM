# apm-thread-monitor 模块

> 同步日期：2026-07-11｜模块名：`thread_monitor`

## 目的

`ThreadMonitorModule` 每 30s 获取 `Thread.getAllStackTraces()`，检查活动线程数量、同名线程数量和 `Thread.State.BLOCKED` 状态。

## 已实现检测

- total thread count > 100 -> `thread_count_spike`
- same thread name count > 5 -> `duplicate_thread`
- BLOCKED thread list non-empty -> `blocked_thread`
- 报告名称、状态和截断 stack
- `registerThreadPool(name, ThreadPoolExecutor)` 后读取真实 pool/active/max/queue/completed；queue ≥ threshold -> `thread_pool_backlog`

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| thread count | 100 |
| duplicate name | 5 |
| interval | 30s |
| stack max | 4000 chars |
| `enableThreadPoolMonitor` | true；只扫描显式注册池 |
| `queueBacklogThreshold` | 100 |
| `enableThreadLeakDetect` | false / deprecated |
| `threadLeakThresholdMs` | 300s / deprecated compatibility |

## 线程与资源

通过 `ApmExecutors` scheduled background executor 串行采样。全线程 stack snapshot 可能产生分配，周期和阈值应按应用规模调优。

## 边界

- BLOCKED 不等于已经证明的死锁；代码没有构建 wait-for graph/cycle。
- 同名线程只是泄漏候选，不证明资源泄漏。
- 不 Hook 任意 Executor；宿主显式注册，unregister/onStop 释放强引用。
- 线程存活时长不能证明 leak，因此通用 leak 字段弃用并默认关闭。

## 测试

Config、数量/同名/BLOCKED 分类及真实 ThreadPoolExecutor queue snapshot 有测试；大规模线程开销和死锁根因仍需设备/系统 trace。
