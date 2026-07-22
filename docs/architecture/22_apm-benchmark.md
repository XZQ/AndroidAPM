# apm-benchmark 架构

> 同步日期：2026-07-22

## 1. 边界

`apm-benchmark` 是不发布到 Maven 的 AndroidX Microbenchmark 与发布预算门。它测量 SDK 自身的持久化热路径，不是宿主 App 的端到端性能测试，也不能替代功耗、热、长稳或 OEM 设备矩阵。

当前覆盖：

- `ApmEventCodec.encode`：一个代表性 event 的 durable 编码；
- `ApmEventCodec.decode`：同一 payload 的 durable 解码；
- `SQLiteEventStore.appendBatch`：dispatcher 最大 drain 大小对应的 32 事件事务。

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
