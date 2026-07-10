# apm-gc-monitor 模块

> 同步日期：2026-07-10｜模块名：`gc_monitor`

## 目的

`GcMonitorModule` 周期读取 `Debug.getRuntimeStat` 和 Runtime heap，比较相邻窗口，识别 GC/分配/Heap churn。

## 采集与判定

- GC count delta
- GC time delta/窗口比例
- used heap 与 growth ratio
- allocated bytes delta/second
- freed bytes / allocated bytes reclaim ratio

任一阈值触发后发出 `memory_churn`，字段标明具体触发维度。

## 默认配置

| 配置 | 默认 |
|---|---:|
| interval | 10s |
| GC count spike | 5 |
| GC time ratio | 10% |
| heap growth | 20% |
| allocation rate | 1024 KiB/s |
| low reclaim rate | 10% |
| allocation/reclaim analysis | 开 |
| stack max | 4000 chars |

## 线程与降级

scheduled background executor 串行采样。`Debug.getRuntimeStat` 在不支持/异常时返回 null 并通过 internal error 记录，模块保留可计算的 heap 维度。

## 边界

- runtime stat key 和可用性受 Android/ART 版本影响。
- 这是进程窗口统计，不提供单次 GC pause trace 或对象分配栈。
- 高分配/低回收是 churn 候选，不直接证明内存泄漏。
- 不能替代 Perfetto/ART allocation profiler。

## 测试

Config、GcStats 和 module 阈值/差分有测试；真实 ART stat、后台限制和长时间准确性需真机验证。
