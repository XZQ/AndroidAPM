# apm-ipc 模块

> 同步日期：2026-07-11｜模块名：`ipc`

## 目的与入口

`IpcModule` 对宿主报告的 Binder 调用完成事件做阈值、主线程、stack 和聚合分析：

```kotlin
module.traceBinderCall(interfaceName, methodName) { service.call() }
// 或已有计时点：
module.onBinderCallComplete(interfaceName, methodName, durationMs)
```

公共 Android API 没有通用 Binder proxy/native hook 安装路径。`enableBinderHook` 已 deprecated 且默认 `false`；显式 wrapper 保留返回值与异常语义。

## 检测

- 普通线程 slow threshold：500ms
- 主线程 threshold：100ms
- main-thread 调用带 stack（最大 4000 chars）
- 所有显式调用进入固定 50 次窗口，输出 count/total/average/max/slow count

事件：`slow_binder_call`, `binder_call_aggregation`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| binder threshold | 500ms |
| main-thread threshold | 100ms |
| aggregation | 开，window 50 |
| `enableBinderHook` | false / deprecated |

## 线程与资源

没有模块自建采样线程。callback 在宿主线程快速判断并 `Apm.emit`；聚合数据受窗口限制。

## 边界

- 只有宿主主动报告的 Binder 调用可见。
- interface/method 命名由宿主提供。
- 不能捕获系统内所有 Binder transaction，也不能替代 Perfetto Binder trace。
- 不使用 hidden API/反射扩大覆盖；系统级全量 transaction 使用 Perfetto。

## 测试

Config、显式 wrapper 返回/异常语义和固定窗口 accumulator 有单元测试；真实 AIDL 场景纳入设备集成测试。
