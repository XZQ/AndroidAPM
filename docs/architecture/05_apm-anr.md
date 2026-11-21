# apm-anr 模块

> 同步日期：2026-11-21｜模块名：`anr`

## 目的

`AnrModule` 使用 Native SIGQUIT flag 与主线程 Watchdog 双通道检测主线程阻塞，随后采集堆栈、尝试读取 traces、分类并去重上报。

## 启动与检测

默认 `enableSigquitDetection=true`：

- 加载 `libapm-anr.so`
- Native signal handler 只做异步信号安全操作：保存/恢复被中断代码的 `errno`、原子写时间戳、`tgkill` 定向转发给 ART Signal Catcher（保留系统 ANR trace dump）
- `SigquitFlagPoller` 在普通线程轮询 flag
- `SigquitAnalysisDispatcher` 调度实际分析，避免在 signal context 执行 JVM 工作

Native 安装失败时启动 Watchdog。Watchdog 按 5s interval 在 main Handler 投递 tick；超过 5s timeout 判定阻塞。

Native 生命周期契约（进程级资源）：

- **引用计数共享**：SIGQUIT 处置是进程级的，同进程多个 `AnrModule` 实例按引用计数共享一个处理器；最后一个注销者才真正卸载，先停的实例不会撤掉仍在运行实例的检测。
- **互斥锁序列化**：注册/注销由 `pthread_mutex` 串行化，防并发 start/stop 竞态；信号处理器内绝不加锁。
- **卸载顺序**：先 join 接收线程，再恢复原始 `sigaction`。顺序不可颠倒——接收线程是唯一解除 SIGQUIT 阻塞的线程，若先恢复原处置（ART 下通常 SIG_DFL），线程退出前到达的 SIGQUIT 会按默认动作杀死宿主进程。
- **会话隔离**：注册与注销都清零未消费时间戳，上一会话遗留信号不会在新会话误报 ANR。

## 分析

1. 捕获 main thread stack
2. 采样 3 次，间隔 100ms
3. 可选读取 `/data/anr/traces.txt`（权限/OEM 可能失败）
4. 依据 stack/sample 关键字分类（顶层 internal 纯函数 `classifyAnrCauseInternal`，可纯 JVM 直测）：判定顺序 LOCK/DEADLOCK → IO → BINDER → CPU → UNKNOWN。BINDER 必须先于 CPU——Binder 阻塞时相邻采样常落在不同代理调用上，先判「采样有变化=CPU」会误分类
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

Watchdog（`apm-anr-watchdog`）、flag poll 和 SIGQUIT analysis（`apm-anr-analysis`，刻意区别于 native 接收线程 `apm-anr-sigquit`，便于 trace 归因）为命名 daemon/measurement 线程。分析可能读取文件和多次采样，但不在 signal handler 或主线程执行。每次模块启动拥有独立、永久取消的 detection session，隔离 tick、blocked 状态、dedup 和分析结果；停止后迟到采样不进入新模块会话。最终 critical 交接使用捕获的 `ApmContext.emitInSession`，业务上下文在门禁外采集，取消不等待任意宿主 store；已经准入的同步交接由 core 撤回屏障负责清理证明。

## 降级与边界

- SIGQUIT 不是唯一 ANR 真相；Watchdog 也可能把长任务判成 ANR 候选。
- traces 文件通常受系统权限限制，失败时使用当前堆栈。
- 原因分类是启发式，不等同于系统/服务端根因分析。
- 多通道通过 fingerprint/window 去重，不保证所有竞争时序只产生一个事件。

## 测试

Config、module、flag poller、analysis dispatcher、原因分类（`AnrCauseClassificationTest`，10 tests，含 BINDER 先于 CPU 的顺序回归）与 critical hand-off 成功/失败边界有单元测试；core 的故障注入还覆盖 critical IPC 在首次 store 异常后保留、重试成功、eventId/priority/fields 不变。Native signal delivery、引用计数/卸载顺序、OEM traces 权限、真实 SQLite fsync 时延和系统 ANR 对齐需真机验证（建议抽查：start→stop→start 不误报、stop 后 SIGQUIT 不杀进程、双实例先后 stop 检测不失效）。

## 时间语义

主线程 blocked duration 与多通道去重窗口使用 `ApmClock` 单调时间；上报事件 timestamp 仍为 epoch。这样系统校时不会制造负卡顿或重复 ANR。
