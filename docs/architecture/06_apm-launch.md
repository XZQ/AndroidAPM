# apm-launch 模块

> 同步日期：2026-11-26｜模块名：`launch`

## 目的与入口

`LaunchModule` 记录冷启动、热/温恢复、首帧和可选阶段耗时。它实现 `Application.ActivityLifecycleCallbacks`；ContentProvider 和 Application 阶段通过公开方法由宿主显式标记。

## 冷启动

基线优先使用 `Process.getStartElapsedRealtime()`（API 24+），避免以 SDK init 时间冒充进程启动。Activity created 后记录首 Activity 阶段，并通过 Choreographer/回退路径报告首帧。

事件：`cold_start`, `first_frame_rendered`, `launch_bottleneck`。

阶段可包含：process start、ContentProvider、Application onCreate、Activity create/resume、first frame。`onContentProviderCreateStart/End` 与 `onAppOnCreateStart/End` 不会自动插入宿主代码。

## 热/温启动

`RelaunchTracker` 在所有 Activity stopped 后记录后台时间：

- background duration < 5s：hot start
- background duration ≥ 5s：warm start

上报耗时是前台恢复链路耗时（`start -> resume`），同时带 `backgroundDurationMs`；`launchTimeoutMs` 仅约束前台恢复链路耗时，不会把长后台停留误判为超时丢弃，也不会把后台停留时长当作启动耗时。

事件：`hot_start`, `warm_start`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| cold/hot/warm | 全开 |
| launch timeout | 30s |
| warm threshold | 5s |
| phase tracking | 开 |
| first frame | 开 |
| cold warn/severe | 2s / 5s |
| ContentProvider tracking | 开，但需调用 API |

## 线程与资源

主要生命周期状态在主线程更新；首帧观察使用一次 Choreographer FrameCallback，注册失败时走主 Handler 回退。模块停止会取消已注册的 callback，并永久关闭该次模块会话；已经取出的迟到 callback 也不能进入重新启动的模块或 runtime。事件通过捕获的 `ApmContext.emitInSession` 异步进入 core dispatcher，宿主业务上下文返回后再校验最终会话门禁。

## 边界

- SDK 无法无侵入捕获所有自定义 ContentProvider/Application 子阶段，需宿主调用。
- `first_frame_rendered` 保留既有事件名，实际观测是注册后的下一次 VSync callback；该回调发生在绘制前，不证明屏幕已经呈现首帧。Handler 回退也只表示调度完成。精确首帧仍需系统 Perfetto/FrameTimeline 或宏基准。
- 多 Activity/透明 Activity/特殊 task 模式需要真机场景验证。
- 冷启动瓶颈分类由已有阶段数据推断。

## 测试

Config、LaunchModule 和 RelaunchTracker 覆盖冷/热/温计算和状态转换；系统进程启动与首帧准确性需设备/宏基准校验。

## 时间语义

LaunchModule 的阶段起止统一使用 `SystemClock.elapsedRealtime()`，与 `Process.getStartElapsedRealtime()` 同基准且都包含 deep sleep，避免和 `nanoTime`/uptime 混用产生设备开机后的累计偏移。模块也向 RelaunchTracker 显式传入同一时基；独立使用该 tracker 时默认仍为 `ApmClock` 单调时间。上报 timestamp 保持 epoch。回归故意分离 elapsed/uptime 并校验各阶段的精确区间。
