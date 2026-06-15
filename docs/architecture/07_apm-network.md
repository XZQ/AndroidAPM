# apm-network 模块架构

> 网络监控：OkHttp Interceptor + EventListener + 全链路耗时 + 聚合统计

## 2026-06-15 实现状态

- Interceptor 默认负责请求汇总，EventListener 默认只负责阶段耗时，避免同一请求被统计两次。
- EventListener 会保留 HTTP 状态码和连接失败信息，并只为慢请求或错误请求输出阶段事件。
- 需要仅使用 EventListener 时，可通过 `reportSummary=true` 显式启用汇总事件。

---

## 类图

```
┌──────────────────────────────────────────────────────┐
│                  NetworkModule                         │
│            (implements ApmModule)                      │
├──────────────────────────────────────────────────────┤
│ - apmContext: ApmContext?                             │
│ - config: NetworkConfig                              │
│ - started: Boolean @Volatile                         │
│                                                      │
│ 统计:                                                 │
│ - totalRequests: AtomicLong                          │
│ - successCount / errorCount: AtomicLong              │
│ - totalDurationMs / maxDurationMs: AtomicLong        │
│ - recentDurations: ConcurrentLinkedQueue<Long>       │
├──────────────────────────────────────────────────────┤
│ + onInitialize(context)                              │
│ + onStart() / onStop()                               │
│ + onRequestComplete(url, method, status, duration...)│
│ + emitAggregate()                                    │
│ + onNetworkPhaseStats(stats: NetworkRequestStats)    │
│ + getStats(): NetworkStats                           │
└──────────────┬──────────────┬────────────────────────┘
               │              │
       ┌───────┘              └────────┐
       ▼                               ▼
┌──────────────────┐  ┌───────────────────────────┐
│ApmNetworkIntercept│  │  ApmEventListener          │
│ (OkHttp Intercept)│  │  (OkHttp EventListener)    │
├──────────────────┤  ├───────────────────────────┤
│- networkModule   │  │- networkModule            │
├──────────────────┤  │- slowThresholdMs          │
│+ intercept(chain) │  │- callTimings: Concurrent  │
│ ┌──────────────┐ │  │  HashMap<Call, CallTiming> │
│ │ 记录开始时间  │ │  ├───────────────────────────┤
│ │ chain.proceed│ │  │+ callStart(call)          │
│ │ 计算耗时     │ │  │+ dnsStart/dnsEnd          │
│ │ 上报结果     │ │  │+ connectStart/connectEnd  │
│ └──────────────┘ │  │+ secureConnectStart/End   │
└──────────────────┘  │+ requestHeadersStart/End  │
                      │+ responseHeadersStart/End │
                      │+ responseBodyStart/End    │
                      │+ callEnd / callFailed     │
                      └───────────────────────────┘

┌─────────────────────┐  ┌─────────────────────┐
│  NetworkConfig      │  │ NetworkRequestStats  │
│  (data class)       │  │ (data class)         │
├─────────────────────┤  ├─────────────────────┤
│ enableNetworkMonitor│  │ url, method, status  │
│ maxPayloadSize      │  │ dnsMs, tcpMs, tlsMs  │
│ slowThresholdMs: 3s │  │ requestHeaderMs      │
│ aggregateWindowSize │  │ responseHeaderMs     │
└─────────────────────┘  │ responseBodyMs       │
                         │ totalMs, error       │
┌─────────────────────┐  └─────────────────────┘
│  NetworkStats       │
│  (data class)       │
├─────────────────────┤
│ totalRequests       │
│ successCount        │
│ errorCount          │
│ avgDurationMs       │
│ maxDurationMs       │
└─────────────────────┘
```

## OkHttp 全链路监控流程

```
┌───────────────────────────────────────────────────────────┐
│              OkHttp 全链路事件监听                          │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  ApmEventListener (每个 Call 一个实例)                     │
│                                                           │
│  ① callStart(call)                                       │
│     └── callTimings[call] = CallTiming(startNs)           │
│                                                           │
│  ② dnsStart → dnsEnd                                     │
│     └── timing.dnsMs = elapsed(dnsStartNs)                │
│                                                           │
│  ③ connectStart → connectEnd                              │
│     └── timing.tcpMs = elapsed(connectStartNs)            │
│                                                           │
│  ④ secureConnectStart → secureConnectEnd                  │
│     └── timing.tlsMs = elapsed(secureStartNs)             │
│                                                           │
│  ⑤ requestHeadersStart → requestHeadersEnd                │
│     └── timing.requestHeaderMs                            │
│                                                           │
│  ⑥ responseHeadersStart → responseHeadersEnd              │
│     └── timing.responseHeaderMs                           │
│                                                           │
│  ⑦ responseBodyStart → responseBodyEnd                    │
│     └── timing.responseBodyMs                             │
│                                                           │
│  ⑧ callEnd / callFailed                                   │
│     └── reportNetworkStats(timing, totalMs, error)        │
│         └── networkModule.onNetworkPhaseStats(stats)      │
│                                                           │
│  总耗时分解:                                               │
│  DNS + TCP + TLS + RequestHeader + ResponseHeader + Body  │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

## ApmNetworkInterceptor 流程

```
intercept(chain)
       │
       ├── request = chain.request()
       ├── url = request.url.toString()
       ├── method = request.method
       ├── startMs = System.nanoTime()
       │
       ├── try { response = chain.proceed(request) }
       │   └── statusCode = response.code
       │
       ├── catch (e: Exception)
       │   └── error = e.message
       │
       ├── durationMs = elapsed(startMs)
       │
       └── networkModule.onRequestComplete(
             url, method, statusCode, durationMs,
             requestSize, responseSize, error
           )
            ├── 慢请求检测 (durationMs >= slowThreshold)
            │   → Apm.emit(ALERT)
            └── 错误检测 (非 2xx / 异常)
                → Apm.emit(ALERT)
```

## 聚合统计流程

```
每 aggregateWindowSize (默认100) 个请求后
       │
       ▼
emitAggregate()
       │
       ├── avgDuration = totalDuration / totalRequests
       │
       └── Apm.emit(
             module = "network",
             name = "network_aggregate",
             kind = METRIC,
             fields = {
               totalRequests,
               successCount,
               errorCount,
               avgDurationMs,
               maxDurationMs,
               errorRate
             }
           )
```
