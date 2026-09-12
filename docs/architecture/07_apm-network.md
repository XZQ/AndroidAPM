# apm-network 模块

> 同步日期：2026-09-07｜模块名：`network`

## 目的与接入

该模块记录请求汇总、OkHttp 阶段耗时和显式 HttpURLConnection 总耗时，但不会自动修改宿主 HTTP 客户端。

四种入口：

```kotlin
val module = NetworkModule()
Apm.register(module)

OkHttpClient.Builder()
    .eventListenerFactory(ApmEventListener.factory(module))

module.onRequestComplete(url, method, statusCode, durationMs)
```

默认 OkHttp EventListener 在 callEnd/callFailed 结算唯一请求 summary，totalMs 包括 body 消费/关闭；requestBodyEnd/responseBodyEnd 提供已完成阶段的实际字节数，失败 body 耗时同样保留。与拦截器同时接入时按 Call/模块协商所有权，避免重复。单独拦截器或显式 reportSummary=false 的兼容组合，在流 EOF/已知长度完成/close/IOException 时只结算一次；它无法观察未进入拦截器的提前取消，因此推荐 listener。headers/body/total 保留独立口径，不在 headers 到达时计为成功。宿主仍负责消费或关闭 body，SDK 不主动读取正文。 本项 clean network 4 suites / 27 tests、lint（无问题）、apiCheck 与文档检查通过。

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

Config/NetworkStats 之外，行为测试直接覆盖停止态 no-op、成功/失败/慢请求分类、累计统计、固定窗口 aggregate、phase threshold/error override、请求与 phase URL/error 截断，以及 HttpURLConnection 成功/HTTP error/transport exception/宿主异常/report failure/fatal 边界。内部 sink 和假 connection 使字段、severity、执行次数与异常身份可在 JVM 中直接断言；真实 loopback OkHttp 的暂停 body、body 截断、成功/HTTP error、提前取消及四种集成组合已有回归；代理/TLS/OEM 网络行为仍需真实环境验证。

## 时间语义

OkHttp interceptor/EventListener 和 HttpURLConnection helper 的总耗时、DNS/connect/TLS/request/response phase 使用 `ApmClock` 单调时间；HTTP-date 与 collector timestamp 仍遵守 epoch/协议语义。

本项增加 requestBodyEnd override（additive ABI，CallTiming constructor/copy/component 未变）。sample 当前使用手动 onRequestComplete 示例，其语义不变。
