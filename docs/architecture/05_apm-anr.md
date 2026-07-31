# apm-anr 模块

> 同步日期：2026-07-31｜模块名：`anr`

## 目的

`AnrModule` 使用 Native SIGQUIT flag 与主线程 Watchdog 双通道检测主线程阻塞，随后采集堆栈、尝试读取 traces、分类并去重上报。

## 启动与检测

默认 `enableSigquitDetection=true`：

- 加载 `libapm-anr.so`
- Native signal handler 只设置原子 flag
- `SigquitFlagPoller` 在普通线程轮询 flag
- `SigquitAnalysisDispatcher` 调度实际分析，避免在 signal context 执行 JVM 工作

Native 安装失败时启动 Watchdog。Watchdog 按 5s interval 在 main Handler 投递 tick；超过 5s timeout 判定阻塞。

## 分析

1. 捕获 main thread stack
2. 采样 3 次，间隔 100ms
3. 可选读取 `/data/anr/traces.txt`（权限/OEM 可能失败）
4. 依据 stack/sample 关键字分类：CPU / IO / LOCK / DEADLOCK / BINDER / UNKNOWN
5. 对 stack 生成 fingerprint
6. 30s 窗口内相同 fingerprint 去重
7. 通过 `Apm.emitCriticalSync` 同步 hand-off `anr_detected`

10s 以上使用更高 severity。

ANR 报告不再进入 shared dispatcher queue，也不经过 sampling、aggregation 或 rate limit。完整事件同步到 uploader 进程 SQLite，或在非 uploader 进程同步发布 critical `.ipc` 文件；只有 hand-off 完成才返回成功，现场线程不执行网络请求。上传进程消费该文件时仍直接走同步 store hand-off；false 或 recoverable 存储异常会保留 ready 文件重试，直到真正接受才删除。false 结果写 `anr_local_handoff` internal error，并由 core 的 `IPC_HANDOFF_FAILURE` / storage reason 计数保留损失证据。

## 默认配置

| 配置 | 默认 |
|---|---:|
| check interval | 5s |
| ANR timeout | 5s |
| SIGQUIT detection | 开 |
| traces reading | 开 |
| classification | 开 |
| dedup window | 30s |
| severe threshold | 10s |
| stack samples | 3 × 100ms |
| max stack | 4000 chars |

## 线程与资源

Watchdog、flag poll 和 SIGQUIT analysis 为命名 daemon/measurement 线程。分析可能读取文件和多次采样，但不在 signal handler 或主线程执行。

## 降级与边界

- SIGQUIT 不是唯一 ANR 真相；Watchdog 也可能把长任务判成 ANR 候选。
- traces 文件通常受系统权限限制，失败时使用当前堆栈。
- 原因分类是启发式，不等同于系统/服务端根因分析。
- 多通道通过 fingerprint/window 去重，不保证所有竞争时序只产生一个事件。

## 测试

Config、module、flag poller、analysis dispatcher 与 critical hand-off 成功/失败边界有单元测试；core 的故障注入还覆盖 critical IPC 在首次 store 异常后保留、重试成功、eventId/priority/fields 不变。Native signal delivery、OEM traces 权限、真实 SQLite fsync 时延和系统 ANR 对齐需真机验证。

## 时间语义

主线程 blocked duration 与多通道去重窗口使用 `ApmClock` 单调时间；上报事件 timestamp 仍为 epoch。这样系统校时不会制造负卡顿或重复 ANR。
