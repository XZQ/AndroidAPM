# apm-anr 模块

> 同步日期：2026-07-10｜模块名：`anr`

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
7. 发出 `anr_detected`

10s 以上使用更高 severity。

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

Config、module、flag poller、analysis dispatcher 有单元测试；Native signal delivery、OEM traces 权限和系统 ANR 对齐需真机验证。
