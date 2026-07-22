# apm-webview 模块

> 同步日期：2026-07-11｜模块名：`webview`

## 目的与接入

Android 没有进程级 WebView 注册表。`WebviewModule` 对宿主明确指定的实例提供可逆公共 API：

- `install(webView, hostClient, hostChrome)` 安装完整 delegate wrapper
- `uninstall(webView)` 恢复原对象；API 26+ 不覆盖宿主后续主动替换的 client
- `wrapWebViewClient` / `wrapWebChromeClient` 只创建 wrapper
- `evaluateJavascript` 保留宿主 callback 并测量执行耗时

宿主也可继续手动转发：

- `onPageStarted(url)` / `onPageFinished(url)`
- `onJsEvalComplete(url, snippet, durationMs)`
- `onWhiteScreen(url, durationMs)`
- `onJsBridgeCall(url, bridgeName, direction, durationMs, success)`
- `onJsConsoleError(url, message, sourceUrl, line)`

`enableAutoRegister` 为 deprecated compatibility 字段，默认 `false`。不会使用反射扫描或全局接管。

## ResourceWaterfall

`getResourceWaterfall()` 返回资源记录器：支持 page isolation、重复 URL、并发资源、慢资源事件、关键路径和 waterfall 汇总。wrapper 在 `shouldInterceptRequest` 记录开始；公共 API 无法获得普通网络资源的精确完成回调，因此页面完成作为保守上界。tracking map 受 `maxTrackedResources=200` 约束。

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
| auto register | false / deprecated |

## 线程与隐私

导航/chrome callback 运行在宿主调用线程；白屏 deadline 使用 `ApmExecutors` 单线程 scheduler，停止时取消。URL、JS snippet、console error 和 bridge 字段都可能包含隐私，生产必须截断/脱敏。

## 边界

- wrapper 以 `onPageStarted` 到 `onPageCommitVisible/onPageFinished` 的超时作为 suspected white screen，不做隐私敏感的像素抓取；宿主仍可提供更强信号。
- 资源 waterfall 只覆盖 wrapper 或宿主转发可见的资源事件。
- JS Bridge 监控不自动包装任意 `addJavascriptInterface`。
- 页面重定向、SPA 和多 WebView 需要接入方提供稳定 page/session identity。

## 测试

Config、module callback、页面隔离/资源 waterfall 状态，以及 Robolectric install/uninstall 和 delegate preservation 有测试；真实 WebView/OEM 内核/JS 执行仍需设备矩阵。

## 时间语义

页面、资源和显式 JavaScript duration 使用 `ApmClock` 单调时间；page/resource 事件 timestamp 保持 epoch，系统时间跳变不会制造负 waterfall。
