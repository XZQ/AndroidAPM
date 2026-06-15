# 20. apm-otel-exporter — OpenTelemetry 映射适配层

## 模块概述

apm-otel-exporter 将 APM 事件转换为 OpenTelemetry 语义兼容的结构化 Map。模块不依赖 OTel SDK、不创建 exporter，也不直接连接 Collector；宿主应用负责把映射结果交给自己的 OTel SDK/OTLP 管线。

## 核心类

| 类 | 职责 |
|---|------|
| `OtelEventBridge` | 映射入口，按类型生成 Span/Metric/Log 兼容结构 |
| `OtelSpanExporter` | ALERT 事件 → SpanData 兼容 Map |
| `OtelMetricExporter` | METRIC 事件 → Gauge 数据点兼容 Map |
| `OtelConfig` | 桥接配置 |

## 事件映射规则

| APM 事件类型 | OTel 信号 | 映射说明 |
|-------------|----------|---------|
| ALERT | Span | 崩溃/ANR/泄漏 → OTel Span（status=ERROR） |
| METRIC | Metric | 数值字段 → OTel Gauge 数据点 |
| FILE | Log | 文件事件 → LogRecord |
| 所有类型 | Log | 可选，所有事件同时导出为 LogRecord |

## Span 映射规则

| APM 字段 | OTel Span 字段 |
|----------|---------------|
| module | span name |
| name | description |
| timestamp | start/end epoch |
| severity ≥ ERROR | status = ERROR |
| fields | attributes (apm.field.*) |
| extras | attributes (extras.*) |
| globalContext | attributes (context.*) |

## Metric 映射规则

fields 中的每个数值类型字段生成一个独立 Gauge 数据点：

```
metricName = "apm.{module}.{name}.{field_key}"
type = GAUGE
value = 数值
attributes = { apm.severity, apm.priority, apm.scene, ... }
```

## 依赖设计

```kotlin
api(project(":apm-model"))
```

输出为标准 Map 结构，包含 resource、service.name 和 collectorEndpoint 元数据。宿主 OTel 集成层据此构建真正的 SpanData/MetricData/LogRecord 并发送。

## 使用方式

```kotlin
val bridge = OtelEventBridge(OtelConfig(
    serviceName = "my-app",
    endpoint = "http://collector:4317",
    exportSpans = true,
    exportMetrics = true,
    exportLogs = true
))

// 单条映射
val result = bridge.export(apmEvent)

// 批量映射，保留每条事件的日志结果
val batchResult = bridge.exportBatch(eventList)
```

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| enabled | true | 是否启用 |
| serviceName | "android-apm" | OTel 服务名 |
| endpoint | "" | OTLP Collector 地址 |
| resourceAttributes | emptyMap() | 附加 Resource 属性 |
| exportSpans | true | 导出 Span |
| exportMetrics | true | 导出 Metric |
| exportLogs | true | 导出 Log |
