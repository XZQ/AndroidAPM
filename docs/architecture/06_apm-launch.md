# apm-launch 模块

> 同步日期：2026-07-10｜模块名：`launch`

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

上报耗时是前台恢复链路耗时，同时带 `backgroundDurationMs`；不会把后台停留时长当作启动耗时。

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

主要生命周期状态在主线程更新；首帧 listener 使用 ViewTreeObserver/Choreographer。事件仍异步进入 core dispatcher。

## 边界

- SDK 无法无侵入捕获所有自定义 ContentProvider/Application 子阶段，需宿主调用。
- 首帧是应用层可见帧近似，不等同于系统 Perfetto/FrameTimeline 全链。
- 多 Activity/透明 Activity/特殊 task 模式需要真机场景验证。
- 冷启动瓶颈分类由已有阶段数据推断。

## 测试

Config、LaunchModule 和 RelaunchTracker 覆盖冷/热/温计算和状态转换；系统进程启动与首帧准确性需设备/宏基准校验。
