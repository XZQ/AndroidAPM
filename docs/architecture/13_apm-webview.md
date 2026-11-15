# apm-webview 模块

> 同步日期：2026-11-15｜模块名：`webview`

## 目的与接入

Android 没有进程级 WebView 注册表。`WebviewModule` 对宿主明确指定的实例提供可逆公共 API：

- `install(webView, hostClient, hostChrome)` 安装完整 delegate wrapper
- `uninstall(webView)` 恢复原对象；API 26+ 不覆盖宿主后续主动替换的 client。客户端 setter 强制 WebView 创建线程，卸载会捕获安装线程 Looper，跨线程卸载（如 stop/撤回在工作线程）时把恢复 post 回安装线程，避免 `CalledFromWrongThreadException` 让视图带着 wrapper 失去安装记录
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

`getResourceWaterfall()` 返回资源记录器：支持 page isolation、重复 URL、并发资源、慢资源事件、关键路径和 waterfall 汇总。模块持有的 recorder 与 JS Bridge/Console 一致尊重总开关——仅当 `enableWebviewMonitor` 与 `enableResourceWaterfall` 同时开启才创建，禁用总开关时返回 `null`，不留下仍会发事件的活跃 recorder（独立使用请走 `ResourceWaterfall` 公开构造器）。wrapper 在 `shouldInterceptRequest` 记录开始；公共 API 无法获得普通网络资源的精确完成回调，因此页面完成作为保守上界。`maxTrackedResources=200` 是全部页面共享的准入上限，计时与记录在同一短锁内创建；重复 URL 按 FIFO 完成，页面结束清除其全部记录，停止永久关闭旧 recorder。

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

导航/chrome callback 运行在宿主调用线程；后台资源拦截只使用按 WebView 弱身份保存的页面快照，不读取 `WebView.url`。同一个 wrapper 可复用到不同实例而不串导航快照；完成回调在可能阻塞的报告之前关闭资源准入。SDK 观察失败不阻止原 delegate，renderer exit / Safe Browsing / Chrome 回调保持宿主原参数和返回值。

页面计时最多保留 128 个活跃导航，替换、超时、完成和停止都会取消或移除对应 token；visible 页面取消白屏 alarm，但加载计时仍受容量上限约束。单线程 scheduler 开启 `removeOnCancelPolicy`，取消的延时闭包不继续积压。timeout/slow-page 报告绑定捕获 runtime 和永久模块会话，在宿主业务上下文返回后再检查门禁。URL、JS snippet、console error 和 bridge 字段都可能包含隐私，生产必须截断/脱敏。

## 边界

- wrapper 以 `onPageStarted` 到 `onPageCommitVisible/onPageFinished` 的超时作为 suspected white screen，不做隐私敏感的像素抓取；宿主仍可提供更强信号。
- 资源 waterfall 只覆盖 wrapper 或宿主转发可见的资源事件。
- JS Bridge 监控不自动包装任意 `addJavascriptInterface`。
- 页面重定向、SPA 和多 WebView 需要接入方提供稳定 page/session identity。

## 测试

Config、module callback、页面隔离/资源 waterfall 状态，以及 Robolectric install/uninstall 和 delegate preservation 有测试；真实 WebView/OEM 内核/JS 执行仍需设备矩阵。

## 时间语义

页面、资源和显式 JavaScript duration 使用 `ApmClock` 单调时间；page/resource 事件 timestamp 保持 epoch，系统时间跳变不会制造负 waterfall。
