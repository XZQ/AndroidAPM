# apm-fps 模块

> 同步日期：2026-07-22｜模块名：`fps`

## 目的与入口

`FpsModule` 通过 Activity lifecycle 在页面 resumed 时启动 `FpsMonitor`，paused 时停止。monitor 延迟到主线程生命周期创建，避免后台初始化 `Choreographer`。

## 双采集路径

- Choreographer FrameCallback：根据真实 frame interval/refresh rate 统计 expected/actual frame 和 dropped frames
- FrameMetrics（API 支持时）：记录 total/input/animation/layout/draw/sync/command/swap 等阶段

refresh rate 从 Activity display 获取，异常时回退 60 Hz。

## 输出

单调时间窗口形成 `FrameStats`，发出 `fps_stats`：FPS、总帧、掉帧、jank/frozen、refresh rate、scene 和可用的 FrameMetrics breakdown。默认每 1000ms 上报一次，窗口真实时长不随 60/90/120Hz 刷新率或卡顿程度变化；旧 `windowSize` 只为源码兼容保留，不再控制上报节奏。

掉帧 severity 默认按 dropped frame count 分级：moderate 4、severe 10；frozen threshold 300ms。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| jank threshold | 16ms |
| frozen threshold | 300ms |
| report interval | 1000ms |
| `windowSize` | 60 / deprecated compatibility only |
| FPS warning | 30 |
| scene detect | 开 |
| FrameMetrics | 开 |
| drop severity | 开：4/10 |

## 线程与资源

Choreographer、Activity lifecycle 与传入主线程 Handler 的 FrameMetrics listener 都在主线程。FrameMetrics 每帧只更新固定容量 primitive ring 的滚动总量，不创建 `FrameMetricsBreakdown` 或队列节点；每个上报窗口仅创建一个 breakdown。最多保留最近 1024 帧，API 26+ 才读取 intended/actual VSync timestamp 判定 delayed frame，API 24-25 不制造预期异常；注册/注销失败和每会话首次真实读取异常通过 internal error 记录。上报消费端的 recoverable 异常也不会中断后续帧回调，事件仍进入异步 dispatcher。

## 边界

- 这是应用 View/Window 帧近似，不是系统级 FrameTimeline/SurfaceFlinger 全链。
- 16ms config 与高刷新率并不完全等价，实际 dropped frame 计算会结合 refresh rate。
- FrameMetrics 受 API/OEM 支持影响，失败时 Choreographer 路径继续工作。

## 测试

Config、FrameStats、单调时间窗口、timestamp 回退、FrameMetrics primitive rolling accumulator 和 module lifecycle/计算有单元测试；真实 refresh-rate 切换、多窗口、主线程微开销和设备掉帧仍需要 instrumented/performance 测试。

## FPS 定义与时间语义

报告边界使用单调时间。Rendered FPS = 相邻 Choreographer 回调形成的实际 interval 数 / 这些 interval 的真实 elapsed nanoseconds，并按当前 refresh rate 封顶；事件同时输出 `windowDurationMs`。回调数不会再被当成完整 interval 数，collector timestamp 仍为 epoch。
