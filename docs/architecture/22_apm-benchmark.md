# apm-benchmark 架构

> 同步日期：2026-07-23

## 1. 边界

`apm-benchmark` 是不发布到 Maven 的双层性能门：AndroidX Microbenchmark 测量 SDK 持久化热路径；host-driven sample-App campaign 测量端到端启动、主线程、CPU、PSS、磁盘、功耗、热、断网积压和进程重启。两层都只接受完整证据，host 单测或 build-only 不能替代物理设备结果。

当前覆盖：

- `ApmEventCodec.encode`：一个代表性 event 的 durable 编码；
- `ApmEventCodec.decode`：同一 payload 的 durable 解码；
- `SQLiteEventStore.appendBatch`：dispatcher 最大 drain 大小对应的 32 事件事务。

端到端 campaign 由 `run_device_soak.py` 驱动 `apm-sample-app`：

- A/B control 冷进程构造相同 fields map，但完全跳过 SDK 初始化和 `Apm.emit`；
- enabled 冷进程初始化完整示例模块，以固定速率在主线程执行合成事件；
- offline 模式注入永远返回失败的 uploader，保留真实 outbox/retry 路径，但不修改整机网络；
- host 在每个进程段采 `/proc` CPU、`dumpsys meminfo` PSS、app-private `du`、thermal、charge counter 与 app UID `batterystats`；
- 到达重启间隔后 force-stop，再冷启动下一段；SQLite outbox 数据保留；
- Activity 把进程年龄、`Apm.init` 时间、实际时长、操作数和 primitive rolling P50/P95/max 写入 app-private JSON。

## 2. 预算契约

`benchmark-budgets.json` 使用 version 1 JSON schema，为每个完整类名 + 方法名定义：

- `operationCount`：一次 AndroidX measured block 包含的逻辑操作数；
- `maxMedianTimeNs`：整次 measured block 的 median 时间硬上限；
- `maxMedianAllocationCount`：整次 measured block 的 median allocation 硬上限。

当前发布预算：encode 30 µs / 48 allocations，decode 60 µs / 72 allocations，32-event SQLite batch 8 ms / 2,048 allocations。SQLite 预算归一化后是 250 µs/event 与 64 allocations/event；gate 比较的仍是 AndroidX 原始整批 median，避免四舍五入改变判定。

预算是回归上限，不是所有设备的性能承诺。修改预算必须和热路径变更一起评审，不能因一次失败直接放宽。

## 3. Gate 流程

```text
physical device
  -> connectedReleaseAndroidTest
  -> AndroidX benchmarkData.json
  -> verify_benchmark_budgets.py
  -> require every configured method
  -> require timeNs.median + allocationCount.median
  -> compare checked-in ceilings
  -> non-zero exit on missing, malformed, emulator, or over-budget evidence
```

`:apm-benchmark:verifyReleasePerformanceBudgets` 串起设备测试与 host verifier，作为专用物理设备 CI runner 的单一失败门。`:apm-benchmark:verifyBenchmarkBudgetsFromResults` 只验证已存在输出，便于检查采集工件；两者都不传 `--allow-emulator`。

## 4. 证据完整性

Release gate 同时检查 build metadata 与 AndroidX benchmark 名称，拒绝 emulator/generic/sdk_gphone/emu64 身份及 `EMULATOR_` 前缀。`--allow-emulator` 只供本地验证 parser 接线，不能通过 Gradle release gate。

一次接受的物理结果还应保存设备型号、系统 fingerprint、电量、热状态、充电状态、iteration、P50/P90、allocation 和 JSON/trace 哈希。云端 runner、设备授权和长期趋势存储属于外部 CI/设备实验室。

## 5. 测试

`apm-benchmark/tests/test_verify_benchmark_budgets.py` 使用临时 JSON 确定性覆盖：正常物理结果、时间/分配超限、缺失 benchmark、默认拒绝 emulator，以及显式 parser-only override。Android 源编译继续由 Release/AndroidTest Kotlin 任务验证，真实预算只有专用物理设备运行才可验收。

## 6. 端到端预算与失败语义

`device-soak-budgets.json` 冻结 `smoke`、`24h`、`72h` 三个 profile。校验器要求 profile 身份、物理机、offline uploader、累计实际时长、冷进程次数和全部资源字段一致；24/72 小时还要求 app UID `batterystats` 增量或外部功耗仪证据。任一缺项、非有限值、emulator、时长/重启不足或超预算都以非零退出。

当前回归上限是：启动增量 250 ms、`Apm.init` 200 ms、主线程合成操作 P95 2 ms、长稳平均 CPU 10%（smoke 20%）、PSS 增长 64 MiB、app-private 磁盘增长 70 MiB、功耗 300 mAh/hour、thermal status 3（SEVERE；CRITICAL 及以上拒绝）。磁盘上限高于 SQLite 64 MiB live-payload ceiling，用于容纳 page/WAL 元数据但仍能阻止无界增长。预算是回归上限，不是所有 OEM 的 SLA。

`run_device_soak.py --reset-app-data` 只清理明确选择的 sample package，避免旧 outbox 污染基线；不修改网络，不重置系统 batterystats。`--external-power-mah` 只接受外部仪器归属于 enabled 阶段的 mAh，原始仪器工件仍须随 JSON 保存。`verifyDeviceSoakFromResults` 只验证显式传入的工件，不会搜索并误用旧结果。

`test_run_device_soak.py` 覆盖 ActivityManager 解析、UID 映射、跨进程 CPU 加权和功耗/磁盘/PSS 聚合；`test_verify_device_soak.py` 覆盖成功、emulator/online 拒绝、时长/重启不足、资源超限、长稳功耗缺失及 provenance 缺失。它们证明 host 逻辑，不产生真机接受结论。

## 7. 当前物理设备证据

2026-07-23 在物理 Redmi/Xiaomi `22041216UC`（Android 13）上执行同一源码构建：

- 正式 `AndroidBenchmarkRunner` 完成 3/3 测试，JSON verifier 通过 encode `4,640.93 ns / 22.00 allocations`、decode `4,841.81 ns / 46.00 allocations`、32-event SQLite `1,258,990.52 ns / 1,400.21 allocations`；
- 初始两轮完整 `smoke` 都只违反平均 CPU 上限，分别为 `28.425%`、`32.046%`，超过 `20%`；线程级归因定位到 FPS Choreographer 在静态页面持续唤醒主线程；
- FPS 改为 API 24+ event-driven FrameMetrics 主源、失败时才回退 Choreographer 后，保持同一 `20%` 上限不变，两轮完整 smoke 分别以 `12.928%`、`12.362%` CPU 全项通过；APK SHA-256 均为 `e22185f6b09182e5705cea27d80f74f3ac4f05d89ac2223638c02bc4e8f55c1d`；
- `24h`、`72h` 和长稳功耗未执行，因此不存在对应接受结论；
- MIUI 拒绝 Gradle/UTP 的 session-based 测试 APK 安装，但直接安装同一构建 APK 后正式 runner 可运行；该 OEM 安装器失败必须与 benchmark 预算结果分别记录，不能把手工 runner 通过写成 Gradle aggregate task 通过。

当前判定是：microbenchmark 与 device-soak smoke 的物理门禁均通过，且 smoke 使用原 checked-in `20%` 上限，没有预算覆盖或放宽。下一步是执行 `24h` / `72h` 与长稳功耗验收；短 smoke 不能替代这些长 profile。
