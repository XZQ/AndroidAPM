# 19. apm-trace — 手动埋点 Span/Trace API

## 模块概述

apm-trace 提供手动埋点 Span/Trace API，方便业务标记关键路径，自动上报到 APM 管线。

## 核心类

| 类 | 职责 |
|---|------|
| `ApmTrace` | 全局入口，创建 Span Builder |
| `ApmSpan` | Span 实例，记录名称、耗时、层级、属性 |
| `SpanContext` | traceId/spanId/parentSpanId 关联信息 |
| `TraceConfig` | 模块配置（超时、属性上限、自动上报） |
| `IdGenerator` | 128-bit traceId + 64-bit spanId 生成器 |

## API 设计

```kotlin
// 简单用法
val span = ApmTrace.span("payment_checkout")
    .setAttribute("amount", "99.9")
    .start()
// ... do work ...
span.end()

// 嵌套 Span
val parent = ApmTrace.span("order_create").start()
val child = ApmTrace.span("db_insert")
    .setParent(parent)
    .start()
child.end()
parent.end()

// 自动管理生命周期
ApmTrace.traced("network_call") { span ->
    span.setAttribute("url", "https://api.example.com")
    // 业务代码
    doSomething()
    // span 自动 end，异常时自动标记错误
}
```

## Span 数据流

```
ApmTrace.span("name")  →  ApmSpan (未启动)
    .setAttribute()     →  属性存储
    .setParent()        →  设置父 Span
    .start()            →  记录时间戳、生成 traceId/spanId
    .end()              →  计算 duration、上报到 APM 管线
```

## Span 上报格式

Span end 时自动调用 `Apm.emit()` 上报：

| 字段 | 值 |
|------|------|
| module | `trace`（可配置） |
| name | Span 操作名 |
| kind | METRIC |
| severity | DEBUG（正常）/ WARN（错误） |
| fields | traceId, spanId, duration_ms, status, parentSpanId, attr_* |

## ID 生成策略

- **traceId**: 128-bit（32 hex），高 64 位 = 时间戳 XOR 随机数，低 64 位 = 随机数
- **spanId**: 64-bit（16 hex），时间戳低 32 位 XOR 序列计数 XOR 随机数
- 与 W3C TraceContext / OpenTelemetry 兼容

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| enabled | true | 是否启用 |
| maxSpanDurationMs | 0 | 最大持续时间（0 = 不限制） |
| autoReport | true | 是否自动上报 |
| reportModule | "trace" | 上报模块名 |
| maxAttributes | 32 | 单 Span 属性上限 |
