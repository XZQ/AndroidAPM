# apm-network 模块

> 同步日期：2026-09-07｜模块名：`network`

## 目的与接入

该模块记录请求汇总、OkHttp 阶段耗时和显式 HttpURLConnection 总耗时，但不会自动修改宿主 HTTP 客户端。

四种入口：

```kotlin
val module = NetworkModule()
Apm.register(module)

OkHttpClient.Builder()
    .addInterceptor(ApmNetworkInterceptor(module))
    .eventListenerFactory(ApmEventListener.factory(module))

module.onRequestComplete(url, method, statusCode, durationMs)
```

Interceptor 负责请求汇总；EventListener 默认 `reportSummary=false`，只负责 DNS/TCP/TLS/headers/body 阶段，避免双重汇总。也可由集成方调整所有权。

非 OkHttp 调用可在完成连接配置后显式执行：

```kotlin
val connection = URL(endpoint).openConnection() as HttpURLConnection
connection.requestMethod = "GET"

val body = try {
    module.traceHttpUrlConnection(connection) { traced, statusCode ->
        val stream = if (statusCode >= 400) traced.errorStream else traced.inputStream
        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
} finally {
    connection.disconnect()
}
```

`traceHttpUrlConnection` 读取一次 `responseCode` 作为明确执行点，并将宿主 block 耗时计入 total duration。它不消费正文、不 disconnect、不改变超时/重定向配置，也不伪造 HttpURLConnection 无法提供的 DNS/TCP/TLS 分阶段数据。transport `IOException` 保留原异常并报告网络错误；headers 已收到后的宿主解析异常保留真实 HTTP outcome；监控报告的 recoverable 失败不会覆盖宿主结果。

OkHttp 的中间 `connectFailed` 仅表示一次 route attempt 失败，最终 `callEnd` 仍按响应状态统计成功；只有 `callFailed` 表示最终 transport failure。JVM 回归覆盖多地址回退成功、连接复用、重定向以及收到 headers 后 body 失败。测试复用已有 OkHttp 依赖，发布时仍为 compileOnly。

## 采集

- URL/method/status/error/request/response size
- total duration
- DNS/TCP/TLS/request headers/request body/response headers/response body
- success/error/slow request
- 固定窗口聚合：累计总数、成功数、失败数、平均耗时和最大耗时

事件：`network_request`, `network_error`, `network_aggregate`, `network_phase`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| URL/error 最大长度 | 10 KiB |
| slow threshold | 3000ms |
| aggregate window | 100 requests |

## 依赖与线程

OkHttp 为 compileOnly/API 集成依赖；模块不创建网络线程，回调运行在 OkHttp 调用线程并快速进入 report sink。最大耗时与窗口计数使用原子更新，窗口到达后只有一个并发调用者发出 aggregate。

## 边界

- 只覆盖接入该 interceptor/listener、显式 helper 或手动 callback 的请求；没有进程级全局 Hook。
- 不读取 request/response body 内容，`maxPayloadSize` 当前用于 URL/error 文本截断，不是 body capture 大小。
- HttpURLConnection response size 来自 `Content-Length`，未知/分块响应记为 0；需要实际 body 字节数时使用手动 `onRequestComplete`。
- HttpURLConnection helper 不拥有 connection 生命周期；宿主必须在完成响应处理后自行 `disconnect`。
- 不自动采集 URL query/header 中的敏感数据；生产应保持默认 PII sanitization 开启，并在接入层先行清理 URL。
- EventListener 和 Interceptor 同时接入时应保持单一 summary owner。

## 测试

Config/NetworkStats 之外，行为测试直接覆盖停止态 no-op、成功/失败/慢请求分类、累计统计、固定窗口 aggregate、phase threshold/error override、请求与 phase URL/error 截断，以及 HttpURLConnection 成功/HTTP error/transport exception/宿主异常/report failure/fatal 边界。内部 sink 和假 connection 使字段、severity、执行次数与异常身份可在 JVM 中直接断言；真实 OkHttp/HttpURLConnection/连接池/代理/TLS/重定向/OEM 网络行为仍需集成测试。

## 时间语义

OkHttp interceptor/EventListener 和 HttpURLConnection helper 的总耗时、DNS/connect/TLS/request/response phase 使用 `ApmClock` 单调时间；HTTP-date 与 collector timestamp 仍遵守 epoch/协议语义。
