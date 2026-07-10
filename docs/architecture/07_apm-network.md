# apm-network 模块

> 同步日期：2026-07-10｜模块名：`network`

## 目的与接入

该模块记录请求汇总和 OkHttp 阶段耗时，但不会自动修改宿主 OkHttpClient。

三种入口：

```kotlin
val module = NetworkModule()
Apm.register(module)

OkHttpClient.Builder()
    .addInterceptor(ApmNetworkInterceptor(module))
    .eventListenerFactory(ApmEventListener.factory(module))

module.onRequestComplete(url, method, statusCode, durationMs)
```

Interceptor 负责请求汇总；EventListener 默认 `reportSummary=false`，只负责 DNS/TCP/TLS/headers/body 阶段，避免双重汇总。也可由集成方调整所有权。

## 采集

- URL/method/status/error/request/response size
- total duration
- DNS/TCP/TLS/request headers/request body/response headers/response body
- success/error/slow request
- 固定窗口聚合：总数、失败率、慢请求、平均耗时等

事件：`network_request`, `network_error`, `network_aggregate`, `network_phase`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| URL/error 最大长度 | 10 KiB |
| slow threshold | 3000ms |
| aggregate window | 100 requests |

## 依赖与线程

OkHttp 为 compileOnly/API 集成依赖；模块不创建网络线程，回调运行在 OkHttp 调用线程并快速调用 `Apm.emit`。聚合状态使用并发/同步保护。

## 边界

- 只覆盖接入该 interceptor/listener 的 client。
- 不读取 request/response body 内容，`maxPayloadSize` 当前用于 URL/error 文本截断，不是 body capture 大小。
- 不自动采集 URL query/header 中的敏感数据；生产应开启 PII sanitization 并在接入层清理 URL。
- EventListener 和 Interceptor 同时接入时应保持单一 summary owner。

## 测试

Config、NetworkStats、手动入口和阶段计算有测试；真实 OkHttp/连接池/代理/TLS/OEM 网络行为仍需集成测试。
