# apm-ipc 模块

> 同步日期：2026-07-10｜模块名：`ipc`

## 目的与入口

`IpcModule` 对宿主报告的 Binder 调用完成事件做阈值、主线程、stack 和聚合分析：

```kotlin
module.onBinderCallComplete(interfaceName, methodName, durationMs)
```

当前没有通用 Binder proxy/native hook 安装路径。`enableBinderHook=true` 是未完成的声明型配置，不能作为自动采集证明。

## 检测

- 普通线程 slow threshold：500ms
- 主线程 threshold：100ms
- main-thread 调用带 stack（最大 4000 chars）
- 按 interface/method 聚合窗口 50

事件：`slow_binder_call`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| binder threshold | 500ms |
| main-thread threshold | 100ms |
| aggregation | 开，window 50 |
| `enableBinderHook` | true，但无通用 hook 实现 |

## 线程与资源

没有模块自建采样线程。callback 在宿主线程快速判断并 `Apm.emit`；聚合数据受窗口限制。

## 边界

- 只有宿主主动报告的 Binder 调用可见。
- interface/method 命名由宿主提供。
- 不能捕获系统内所有 Binder transaction，也不能替代 Perfetto Binder trace。
- 配置字段应在未来实现 hook 或移除，避免 API 误导。

## 测试

Config 和 module threshold/main-thread/aggregation 行为有单元测试；真实 Binder 自动采集当前不存在。
