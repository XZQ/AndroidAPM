# apm-webview 模块

> 同步日期：2026-07-10｜模块名：`webview`

## 目的与接入

`WebviewModule` 提供 WebView 事件分析 API，但不自动替换/注册 WebViewClient、WebChromeClient 或 JS Bridge。宿主需转发：

- `onPageStarted(url)` / `onPageFinished(url)`
- `onJsEvalComplete(url, snippet, durationMs)`
- `onWhiteScreen(url, durationMs)`
- `onJsBridgeCall(url, bridgeName, direction, durationMs, success)`
- `onJsConsoleError(url, message, sourceUrl, line)`

`enableAutoRegister=true` 当前没有对应通用 runtime registration 实现，不能理解为注册模块后自动覆盖所有 WebView。

## ResourceWaterfall

`getResourceWaterfall()` 返回资源记录器：支持 page isolation、重复 URL、并发资源、慢资源事件、关键路径和 waterfall 汇总。tracking map 受 `maxTrackedResources=200` 约束。

事件：`slow_page_load`, `slow_js_execution`, `white_screen`, `slow_js_bridge`, `js_console_error`, `slow_resource`, `resource_waterfall`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| page load | 5000ms |
| JS execution | 2000ms |
| white screen | 3000ms |
| URL max | 500 chars |
| JS Bridge | 开，500ms |
| resource waterfall | 开，slow 3000ms |
| JS console | 开 |
| max resources | 200 |

## 线程与隐私

callback 运行在宿主调用线程；内部状态使用并发结构。URL、JS snippet、console error 和 bridge 字段都可能包含隐私，生产必须截断/脱敏。

## 边界

- 白屏判定信号由宿主提供，SDK 不做像素抓取。
- 资源 waterfall 只覆盖宿主转发的资源事件。
- JS Bridge 监控不自动包装任意 `addJavascriptInterface`。
- 页面重定向、SPA 和多 WebView 需要接入方提供稳定 page/session identity。

## 测试

Config、module callback、页面隔离/资源 waterfall 状态有测试；真实 WebView/OEM 内核/JS 注入需要 instrumented 测试。
