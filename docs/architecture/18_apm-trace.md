# apm-trace 模块

> 同步日期：2026-07-10｜默认上报 module：`trace`

## 目的

`apm-trace` 提供手动业务 Span API，不自动追踪任意函数/协程/线程传播。Span 结束后通过现有 `Apm.emit` 进入统一 outbox/上传链路。

## API

```kotlin
val parent = ApmTrace.startSpan("checkout")
val child = ApmTrace.span("db_query")
    .setParent(parent)
    .setAttribute("table", "orders")
    .start()

child.end()
parent.end()

val result = ApmTrace.traced("payment") { span ->
    span.setAttribute("provider", "example")
    executePayment()
}
```

`traced` 在异常时 `setError`，finally 中 `end` 后继续抛原异常。

## Context 与 ID

- traceId：128 bit hex
- spanId：64 bit hex
- child 复用 parent traceId 并保存 parentSpanId
- 当前只通过显式 `setParent` 传播，没有 ThreadLocal/current span/context carrier

## Span 生命周期

- `start` 生成 context 和 start timestamp
- `setAttribute` 超过 32 项时移除最旧 key
- `setError` 设置 ERROR 与 error attribute
- `end` 幂等，记录 end timestamp 并按配置上报
- `maxSpanDurationMs` 只在 `end` 时标记 `timeout=true`，代码不会自动定时结束 Span

## 上报

事件 kind=METRIC，priority=NORMAL：

- `traceId`, `spanId`, optional `parentSpanId`
- `duration_ms`, `status`
- `attr_<key>`
- OK -> DEBUG；ERROR -> WARN

## 默认配置

| 配置 | 默认 |
|---|---:|
| enabled | true |
| max duration | 0（不限） |
| auto report | true |
| report module | `trace` |
| max attributes | 32 |

## 边界与测试

没有自动 instrumentation、跨线程 context、sampling 或 W3C header 注入。`ApmSpanTest` 覆盖 ID、parent、attributes、error、duration 和幂等 end。
