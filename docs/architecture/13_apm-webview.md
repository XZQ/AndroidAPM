# apm-webview 模块架构

> WebView 监控：页面加载耗时 + JS 执行耗时 + 白屏检测

---

## 类图

```
┌──────────────────────────────────────────┐
│          WebviewModule                    │
│       (implements ApmModule)              │
├──────────────────────────────────────────┤
│ - config: WebviewConfig                  │
│ - apmContext: ApmContext?                 │
│ - started: Boolean @Volatile             │
│ - pageLoadStartMap: HashMap<Url, Time>   │
├──────────────────────────────────────────┤
│ + onInitialize(context)                  │
│ + onStart() / onStop()                   │
│ + onPageStarted(url)                     │
│ + onPageFinished(url)                    │
│ + onJsEvalComplete(url, js, duration)    │
│ + onWhiteScreen(url, duration)           │
└──────────────────────────────────────────┘

┌──────────────────────────────────┐
│  WebviewConfig (data class)      │
├──────────────────────────────────┤
│ enableWebviewMonitor: true       │
│ pageLoadThresholdMs: 5000        │
│ jsExecutionThresholdMs: 2000     │
│ whiteScreenThresholdMs: 3000     │
│ maxUrlLength: 500                │
│ enableAutoRegister: true         │
└──────────────────────────────────┘
```

## 检测流程

```
┌────────────────────────────────────────────────────────┐
│                WebView 三维检测                         │
├────────────────────────────────────────────────────────┤
│                                                        │
│  1. 页面加载耗时                                       │
│     onPageStarted(url)                                 │
│       └── pageLoadStartMap[url] = now                  │
│     onPageFinished(url)                                │
│       ├── duration = now - pageLoadStartMap[url]       │
│       ├── pageLoadStartMap.remove(url)                 │
│       └── if (duration >= 5000ms)                      │
│           → emit("slow_page_load", WARN)               │
│                                                        │
│  2. JS 执行耗时                                        │
│     onJsEvalComplete(url, jsSnippet, durationMs)       │
│       └── if (durationMs >= 2000ms)                    │
│           → emit("slow_js", WARN)                      │
│           fields: { url, jsSnippet(截取200字), duration }│
│                                                        │
│  3. 白屏检测                                           │
│     onWhiteScreen(url, durationMs)                     │
│       └── if (durationMs >= 3000ms)                    │
│           → emit("white_screen", ERROR)                │
│           fields: { url, duration }                    │
│                                                        │
└────────────────────────────────────────────────────────┘
```

## 集成方式

```
WebViewClient 子类:
       │
       ├── onPageStarted(view, url, favicon)
       │   └── webviewModule.onPageStarted(url)
       │
       ├── onPageFinished(view, url)
       │   └── webviewModule.onPageFinished(url)
       │
       └── WebView.evaluateJavascript(js, callback)
           └── 记录执行时间
               └── webviewModule.onJsEvalComplete(url, js, duration)
```
