# apm-otel-exporter 模块

> 同步日期：2026-07-10

## 真实边界

该模块是无 OTel SDK 依赖的数据映射层，不是网络 exporter。它把 `ApmEvent` 转成宿主可继续转换的 `Map<String, Any?>`；配置中的 endpoint 只作为 metadata 附加，不会创建 OTLP client。

## 路由

`OtelEventBridge.export`：

- ALERT -> `OtelSpanExporter.toSpanData`（当 exportSpans）
- METRIC -> `OtelMetricExporter.toMetricData`（当 exportMetrics）
- FILE -> 没有专门 signal；可通过通用 Log 输出
- 所有 kind -> LogRecord-compatible Map（当 exportLogs）

`exportBatch` 合并每个事件的 spans/metrics/logs，不丢中间 log；兼容属性 `log` 返回最后一条。

## 映射内容

Span/Metric/Log 带：

- service/resource attributes
- module/name/kind/severity/priority
- process/thread/scene/foreground
- fields/extras
- timestamp/duration/status（按 signal 可用字段）
- collectorEndpoint metadata

当事件没有 trace/span id 时，Span exporter 基于事件内容生成本地 ID；这不等于分布式上下文传播。

## 默认配置

| 配置 | 默认 |
|---|---:|
| enabled | true |
| service name | `android-apm` |
| endpoint | 空 |
| spans/metrics/logs | 全开 |
| resource attributes | 空 |

## 宿主集成

```kotlin
val bridge = OtelEventBridge(OtelConfig(serviceName = "my-app"))
val mapped = bridge.export(event)

// 宿主负责把 mapped 转成自己的 OTel SDK Span/Metric/Log 对象并发送。
```

## 边界

- 不依赖 OTel SDK。
- 不发送 OTLP HTTP/gRPC。
- 不管理 BatchSpanProcessor、MetricReader、LogRecordProcessor。
- 不注入/提取 W3C trace headers。
- durable fields round-trip 后数值可能为 String，宿主需要 schema/coercion 策略。

## 测试

`OtelSpanExporterTest`, `OtelMetricExporterTest`, `OtelEventBridgeTest` 覆盖 signal 路由、resource、batch log 保留和配置开关。
