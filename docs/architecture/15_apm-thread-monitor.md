# apm-thread-monitor 模块

> 同步日期：2026-07-10｜模块名：`thread_monitor`

## 目的

`ThreadMonitorModule` 每 30s 获取 `Thread.getAllStackTraces()`，检查活动线程数量、同名线程数量和 `Thread.State.BLOCKED` 状态。

## 已实现检测

- total thread count > 100 -> `thread_count_spike`
- same thread name count > 5 -> `duplicate_thread`
- BLOCKED thread list non-empty -> `blocked_thread`
- 报告名称、状态和截断 stack

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| thread count | 100 |
| duplicate name | 5 |
| interval | 30s |
| stack max | 4000 chars |
| `enableThreadPoolMonitor` | true（无 runtime 实现） |
| `queueBacklogThreshold` | 100（未消费） |
| `enableThreadLeakDetect` | true（无基于 300s 的 runtime tracker） |
| `threadLeakThresholdMs` | 300s（未消费） |

## 线程与资源

通过 `ApmExecutors` scheduled background executor 串行采样。全线程 stack snapshot 可能产生分配，周期和阈值应按应用规模调优。

## 边界

- BLOCKED 不等于已经证明的死锁；代码没有构建 wait-for graph/cycle。
- 同名线程只是泄漏候选，不证明资源泄漏。
- 当前没有 ThreadPoolExecutor queue hook/backlog tracker。
- `enableThreadLeakDetect` 与 `threadLeakThresholdMs` 目前没有对应生命周期状态机。

## 测试

Config 和 module 的数量/同名/BLOCKED 分类有测试；真实大规模线程开销和死锁根因需设备/系统 trace。
