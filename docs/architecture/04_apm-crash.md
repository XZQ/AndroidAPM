# apm-crash 模块

> 同步日期：2026-09-12｜模块名：`crash`

## 目的与入口

`CrashModule` 捕获 Java 未处理异常，按配置启用 Native signal monitor，并在 API 30+ 读取 `ApplicationExitInfo` 补充上次进程退出原因。

## Java crash

启动时保存原 `Thread.UncaughtExceptionHandler` 并安装 wrapper：

1. 截断 throwable stack（默认 4000 chars）
2. `Apm.emitCriticalSync("java_crash", FATAL, CRITICAL)`
3. 调用原 handler

同步路径只保证本地 durable hand-off，不在 crash thread 做网络。true 表示完整事件已同步到本进程 SQLite，或已原子发布为子进程 critical `.ipc`；false 记录 `crash_local_handoff`。recoverable emit 异常记录 `crash_handler_emit`。原 handler 位于 `finally`，所以 false、recoverable 异常乃至 `OutOfMemoryError` 等 fatal VM error 都不会跳过宿主崩溃链；fatal error 在委托返回后仍原样传播，不伪装成普通 telemetry failure。

## Native crash

`enableNativeCrash=false` 默认关闭。开启后：

- 加载 `libapm_crash.so`
- JNI 静态绑定由 contract test 校验
- `sigaction` 注册致命 signal
- 默认恢复原 handler 并重抛，让系统生成 tombstone
- `enableUnsafeNativeSignalCallback=true` 才允许 signal handler 中的 JNI callback，默认关闭
- 下次启动扫描 tombstone 并发出事件

Native target 具备 16 KiB page-size linker alignment。

## ApplicationExitInfo

`collectExitInfo=true` 默认开启；API 30+ 读取 ANR、crash、low-memory/system kill 等原因。trace 最大读取 64 KiB，并用 timestamp store 避免重复消费。

`app_exit` 使用系统记录的退出时间和历史进程名，线程名为 `unknown`，不携带采集时的业务上下文。严格 V3 使用退出前写入 `ActivityManager.setProcessStateSummary` 的完整 occurrence 五字段：serviceVersion、versionCode、appBuild、variant、匿名 installationId；不把当前 release 或 Native frames 填入历史记录。摘要使用带版本标记的严格 UTF-8 编码，总长度最多 128 字节。任一字段无法完整容纳、摘要缺失/损坏/属于其他组件时，历史身份为未知；V3 在入队前拒绝该记录并计入 `HISTORICAL_OCCURRENCE_UNAVAILABLE`，且推进处理水位，避免每次启动反复丢弃同一记录。不会自动降级 V2。显式 V2/旧协议保留 `occurrenceStatus=UNKNOWN` 的历史事实。

V3 且 `collectExitInfo=true` 时默认使用当前进程的系统摘要槽，启动写入一次，模块停止/撤回同意时尝试清空。宿主已有其他组件使用该槽时，必须通过新增 overload 关闭 SDK 写入：

```kotlin
Apm.register(CrashModule(CrashConfig(), writeExitIdentitySummary = false))
```

关闭写入也关闭 SDK 对该槽的清理；已有系统历史记录仍可读取。该选项不改变 `CrashConfig` 原 constructor/copy/component ABI。系统已保存的退出记录可能包含匿名 installationId，SDK 不能删除这些 OS 历史记录；`storageCleared` 仅证明 SDK 存储清理，不代表系统退出历史被擦除。多进程接入由宿主在各进程分别初始化和传播同意状态。

后台采集器在 trace 读取前后检查会话状态，最终交接与模块停止互斥，并捕获原 `ApmContext`。迟到读取不能通过全局 emitter 进入重新初始化的会话。历史采集仍是 best effort：系统留存、时间戳水位、队列/存储预算和旧记录保留期会限制覆盖率，不承诺退出历史的精确计数或完整补报。

## 配置默认值

| 配置 | 默认 |
|---|---:|
| Java crash | 开 |
| Native crash | 关 |
| unsafe signal callback | 关 |
| max stack | 4000 chars |
| collect exit info | 开 |
| max exit trace | 64 KiB |

## 事件

`java_crash`, `native_crash`, `tombstone_crash`, `app_exit`。

## 降级与边界

- Native 库加载/安装失败时 Java crash 仍可用并记录 internal error。
- 安全重抛优先于 signal handler 内复杂逻辑。
- 仓库没有符号表上传、服务器端 tombstone 解析和聚合服务。
- `ApplicationExitInfo` 依赖系统保留记录，不能覆盖所有 OEM 行为。

## 测试

`CrashConfigTest`, `CrashCriticalHandoffTest`, `NativeCrashMonitorJniContractTest`, `ExitReasonCollectorTest` 覆盖配置、同步 hand-off 成功/false/recoverable/fatal 与原 handler 委托、JNI 名称/绑定和退出原因映射。`ExitOccurrenceSummaryTest` 覆盖 UTF-8/128 字节与损坏输入；`CrashExitHistoryTest` 通过真实模块/dispatcher 和 Robolectric 系统历史，覆盖旧 release/worker 身份、未知身份计数、显式 V2、摘要写入 opt-out、超限、撤回清理和阻塞 trace 的停止/重启。真实 signal/tombstone/symbolization 需真机验证。

## 时间语义

tombstone 文件 `lastModified` 与事件 timestamp 保持 epoch，以便与文件系统和 collector 对齐；轮询节流使用单调时间。Native SIGQUIT/ANR marker 同样使用 `CLOCK_MONOTONIC` 计算进程内间隔。
