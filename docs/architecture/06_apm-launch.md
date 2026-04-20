# apm-launch 模块架构

> 启动监控：6 阶段冷启动 + 热/温启动恢复耗时 + 首帧检测 + 瓶颈分析

---

## 类图

```
┌──────────────────────────────────────────────────────────┐
│                 LaunchModule                              │
│        (implements ApmModule, ActivityLifecycleCallbacks) │
├──────────────────────────────────────────────────────────┤
│ - apmContext: ApmContext?                                 │
│ - config: LaunchConfig                                   │
│ - mainHandler: Handler                                   │
│                                                          │
│ 时间戳锚点:                                               │
│ - processStartMs: Long                                   │
│ - appOnCreateStartMs / appOnCreateEndMs: Long            │
│ - firstActivityOnCreateMs / firstActivityOnResumeMs: Long│
│ - firstFrameRenderedMs: Long                             │
│                                                          │
│ 状态:                                                     │
│ - firstActivityCreated: Boolean                          │
│ - firstFrameRendered: Boolean                            │
│ - isStopped: Boolean                                     │
│ - activityStoppedTime: Long                              │
│ - startedActivityCount: Int                              │
│ - relaunchTracker: RelaunchTracker                       │
│                                                          │
│ ContentProvider 追踪:                                     │
│ - contentProviderTotalMs: Long                           │
│ - contentProviderCount: Int                              │
├──────────────────────────────────────────────────────────┤
│ + onInitialize(context)                                  │
│ + onStart()                                              │
│ + onStop()                                               │
│ + onContentProviderCreateStart/End(providerName)         │
│ + onAppOnCreateStart/End()                               │
│ + onActivityCreated/Resumed/Stopped/...                  │
│ + registerFirstFrameListener(activity)                   │
│ + reportFirstFrame()                                     │
│ + reportLaunchBottleneck(fields)                         │
└──────────────────────────────────────────────────────────┘
```

## 冷启动 6 阶段流程

```
┌───────────────────────────────────────────────────────────────┐
│                     冷启动 6 阶段追踪                          │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌── Phase 1 ──┐                                              │
│  │ Process Start│ ← System.currentTimeMillis()               │
│  └──────┬───────┘                                              │
│         │                                                      │
│  ┌── Phase 2 ──┐                                              │
│  │ ContentProvider│                                            │
│  │ onCreate      │ ← onContentProviderCreateStart/End()      │
│  └──────┬───────┘   记录每个 Provider 耗时                    │
│         │                                                      │
│  ┌── Phase 3 ──┐                                              │
│  │ Application  │                                              │
│  │ onCreate    │ ← onAppOnCreateStart/End()                  │
│  └──────┬───────┘                                              │
│         │                                                      │
│  ┌── Phase 4 ──┐                                              │
│  │ Activity    │                                               │
│  │ onCreate   │ ← onActivityCreated()                        │
│  │ onResume   │ ← onActivityResumed()                        │
│  └──────┬───────┘                                              │
│         │                                                      │
│  ┌── Phase 5 ──┐                                              │
│  │ 首帧绘制    │ ← Choreographer.postFrameCallback()         │
│  │ First Frame │   检测第一个 VSync 回调                      │
│  └──────┬───────┘                                              │
│         │                                                      │
│  ┌── Phase 6 ──┐                                              │
│  │ 首帧渲染    │ ← 降级方案: Handler.postDelayed(100ms)       │
│  │ Rendered    │   如果 Choreographer 不可用                   │
│  └──────────────┘                                              │
│                                                               │
│  总耗时 = firstFrameRenderedMs - processStartMs               │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

## 热/温启动检测流程

```
┌──────────────────────────────────────────────────────────┐
│                  热/温启动检测                             │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Activity.onStopped()                                    │
│       │                                                  │
│       ├── activityStoppedTime = now                      │
│       └── isStopped = true                               │
│                                                          │
│  Activity.onActivityStarted() (再次启动)                  │
│       │                                                  │
│       ├── if (!isStopped) → 不是恢复路径                   │
│       │                                                  │
│       ├── backgroundDuration = now - activityStoppedTime │
│       │                                                  │
│       ├── if (backgroundDuration < warmStartThresholdMs) │
│       │   └── 热启动 (默认 < 5s)                         │
│       │       → 标记 launchType="hot"                    │
│       │                                                  │
│       └── else                                           │
│           └── 温启动 (>= 5s)                             │
│               → 标记 launchType="warm"                   │
│                                                          │
│  Activity.onResumed()                                    │
│       └── launchDuration = resumedAt - startedAt         │
│           → 上报 duration + backgroundDurationMs         │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 瓶颈分析

```
reportFirstFrame()
       │
       ├── 计算 6 个阶段各自的耗时
       │
       ├── 计算总耗时 totalMs
       │
       ├── 严重等级:
       │   ├── >= 5s → ERROR (严重)
       │   └── >= 2s → WARN  (警告)
       │
       ├── 瓶颈分析:
       │   └── 找出耗时最长的阶段
       │       └── 如果占比 > 40% → 标记为瓶颈
       │           → reportLaunchBottleneck()
       │
       └── Apm.emit(
             module = "launch",
             name = "cold_start",
             fields = {
               totalMs,
               phase1_processStart,
               phase2_contentProvider,
               phase3_appOnCreate,
               phase4_activityCreate,
               phase5_activityResume,
               phase6_firstFrame,
               bottleneck     // 瓶颈阶段名
             }
           )
```
