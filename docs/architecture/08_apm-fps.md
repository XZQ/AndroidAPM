# apm-fps 模块架构

> FPS 监控：Choreographer VSync + FrameMetrics + 掉帧/卡顿/冻结分级

---

## 类图

```
┌──────────────────────────────────────────────┐
│             FpsModule                         │
│    (implements ApmModule, ActivityLifecycle)  │
├──────────────────────────────────────────────┤
│ - apmContext: ApmContext?                     │
│ - config: FpsConfig                          │
│ - fpsMonitor: FpsMonitor                     │
│ - currentScene: String                       │
├──────────────────────────────────────────────┤
│ + onInitialize(context)                      │
│ + onStart() / onStop()                       │
│ + onActivityResumed(activity)                │
│   └── updateRefreshRate + bindWindow         │
│ + onActivityPaused(activity)                 │
│   └── unbindWindow + reportAndReset          │
│ + onFrameStats(stats: FrameStats)            │
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│                   FpsMonitor                          │
├──────────────────────────────────────────────────────┤
│ - choreographer: Choreographer                       │
│ - mainHandler: Handler                               │
│ - monitoring: Boolean @Volatile                      │
│                                                      │
│ 帧计数:                                               │
│ - frameCount / droppedFrames / jankCount / frozenCount│
│ - lastFrameTimeNanos: Long                           │
│ - maxDropSeverity: Int                               │
│                                                      │
│ 刷新率:                                               │
│ - refreshRate: Float (默认 60)                       │
│ - frameDurationNanos: Long                           │
│                                                      │
│ FrameMetrics:                                        │
│ - trackedWindow: Window? @Volatile                   │
│ - frameMetricsListener: OnFrameMetricsAvailable?     │
├──────────────────────────────────────────────────────┤
│ + start() / stop()                                   │
│ + setRefreshRate(rate)                               │
│ + bindWindow(window) / unbindWindow()                │
│ + reportAndReset()                                   │
│ + registerFrameMetrics(window)                       │
│ + extractFrameMetrics(frameMetrics)                  │
└──────────────────────────────────────────────────────┘
```

## FPS 检测流程

```
┌──────────────────────────────────────────────────────────┐
│                  FPS 双引擎检测架构                        │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  引擎 1: Choreographer VSync 回调                        │
│  ┌──────────────────────────────────────────────┐       │
│  │  Choreographer.getInstance()                  │       │
│  │    .postFrameCallback { doFrame(nanos) }      │       │
│  │                                               │       │
│  │  doFrame(frameTimeNanos):                     │       │
│  │    ├── interval = frameTimeNanos - lastFrame  │       │
│  │    ├── expectedFrames = interval / frameDur   │       │
│  │    ├── dropped = expectedFrames - 1           │       │
│  │    │                                         │       │
│  │    ├── 帧分类:                                │       │
│  │    │   ├── dropped <= 0 → 正常帧             │       │
│  │    │   ├── 1~3 帧 → 小卡顿                    │       │
│  │    │   │   droppedFrames++                    │       │
│  │    │   ├── 4~17 帧 (>=16ms,<300ms) → Jank   │       │
│  │    │   │   jankCount++                        │       │
│  │    │   └── >= 18 帧 (>=300ms) → Frozen       │       │
│  │    │       frozenCount++                      │       │
│  │    │                                         │       │
│  │    ├── frameCount++                           │       │
│  │    └── postFrameCallback(this) ← 持续监听     │       │
│  └──────────────────────────────────────────────┘       │
│                                                          │
│  引擎 2: Window FrameMetrics (API 24+)                  │
│  ┌──────────────────────────────────────────────┐       │
│  │  window.addOnFrameMetricsAvailableListener(  │       │
│  │    { window, frameMetrics ->                  │       │
│  │       extractFrameMetrics(frameMetrics)       │       │
│  │       ├── measureLayoutNanos                  │       │
│  │       ├── drawNanos                           │       │
│  │       ├── syncNanos                           │       │
│  │       ├── swapBuffersNanos                    │       │
│  │       └── delayedFrames                       │       │
│  │    }                                          │       │
│  │  )                                            │       │
│  └──────────────────────────────────────────────┘       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 掉帧严重度分级

```
┌─────────────────────────────────────────┐
│         Drop Severity 分级               │
├─────────────────────────────────────────┤
│                                         │
│  DROP_SEVERITY_NONE (0)                 │
│    └── 无掉帧                           │
│                                         │
│  DROP_SEVERITY_MINOR (1)                │
│    └── 掉帧 1~3 帧                      │
│                                         │
│  DROP_SEVERITY_MODERATE (2)             │
│    └── 掉帧 4~9 帧 (moderate阈值=4)     │
│                                         │
│  DROP_SEVERITY_SEVERE (3)               │
│    └── 掉帧 >= 10 帧 (severe阈值=10)    │
│                                         │
└─────────────────────────────────────────┘
```

## 窗口统计上报

```
Activity.onPause → reportAndReset()
       │
       ├── fps = frameCount / elapsedSeconds
       │
       ├── Apm.emit(
       │     module = "fps",
       │     name = "fps_stats",
       │     kind = METRIC,
       │     severity = if (fps < fpsWarnThreshold) WARN else INFO,
       │     fields = {
       │       fps,
       │       droppedFrames,
       │       jankCount,
       │       frozenCount,
       │       frameCount,
       │       scene = currentScene,
       │       refreshRate,
       │       dropSeverity,
       │       frameMetricsBreakdown  ← FrameMetrics 详细耗时
       │     }
       │   )
       │
       └── 重置所有计数器
```
