# apm-crash 模块

> 同步日期：2026-07-10｜模块名：`crash`

## 目的与入口

`CrashModule` 捕获 Java 未处理异常，按配置启用 Native signal monitor，并在 API 30+ 读取 `ApplicationExitInfo` 补充上次进程退出原因。

## Java crash

启动时保存原 `Thread.UncaughtExceptionHandler` 并安装 wrapper：

1. 截断 throwable stack（默认 4000 chars）
2. `Apm.emitCriticalSync("java_crash", FATAL, CRITICAL)`
3. 调用原 handler

同步路径只保证本地 durable hand-off，不在 crash thread 做网络。

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

`CrashConfigTest`, `NativeCrashMonitorJniContractTest`, `ExitReasonCollectorTest` 覆盖配置、JNI 名称/绑定和退出原因映射；真实 signal/tombstone/symbolization 需真机验证。
