# apm-fps 模块

> 同步日期：2026-07-10｜模块名：`fps`

## 目的与入口

`FpsModule` 通过 Activity lifecycle 在页面 resumed 时启动 `FpsMonitor`，paused 时停止。monitor 延迟到主线程生命周期创建，避免后台初始化 `Choreographer`。

## 双采集路径

- Choreographer FrameCallback：根据真实 frame interval/refresh rate 统计 expected/actual frame 和 dropped frames
- FrameMetrics（API 支持时）：记录 total/input/animation/layout/draw/sync/command/swap 等阶段

refresh rate 从 Activity display 获取，异常时回退 60 Hz。

## 输出

固定 window 形成 `FrameStats`，发出 `fps_stats`：FPS、总帧、掉帧、jank/frozen、refresh rate、scene 和可用的 FrameMetrics breakdown。

掉帧 severity 默认按 dropped frame count 分级：moderate 4、severe 10；frozen threshold 300ms。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| jank threshold | 16ms |
| frozen threshold | 300ms |
| window | 60 frames |
| FPS warning | 30 |
| scene detect | 开 |
| FrameMetrics | 开 |
| drop severity | 开：4/10 |

## 线程与资源

Choreographer 和 Activity lifecycle 在主线程；FrameMetrics listener 按平台回调。上报进入异步 dispatcher。注册/注销异常通过 internal error 记录。

## 边界

- 这是应用 View/Window 帧近似，不是系统级 FrameTimeline/SurfaceFlinger 全链。
- 16ms config 与高刷新率并不完全等价，实际 dropped frame 计算会结合 refresh rate。
- FrameMetrics 受 API/OEM 支持影响，失败时 Choreographer 路径继续工作。

## 测试

Config、FrameStats 和 module lifecycle/计算有单元测试；真实 refresh-rate 切换、多窗口和设备掉帧需要 instrumented/performance 测试。
