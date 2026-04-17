# apm-render 模块架构

> 渲染监控：View 树数量/层级深度检测 + 过度绘制

---

## 类图

```
┌──────────────────────────────────────────────────┐
│           RenderModule                             │
│    (implements ApmModule, ActivityLifecycle)      │
├──────────────────────────────────────────────────┤
│ - config: RenderConfig                           │
│ - apmContext: ApmContext?                         │
│ - mainHandler: Handler                           │
│ - monitoring: Boolean @Volatile                  │
├──────────────────────────────────────────────────┤
│ + onInitialize(context)                          │
│ + onStart() → 注册 ActivityLifecycleCallbacks    │
│ + onStop() → 注销回调                            │
│ + onActivityCreated(activity)                    │
│ + inspectViewTree(activity)                      │
│ + traverseViewTree(view, depth)                  │
│ + reportViewCountSpike(stats)                    │
│ + reportDeepHierarchy(stats)                     │
└──────────────────────────────────────────────────┘

┌─────────────────────┐  ┌─────────────────────┐
│  RenderConfig       │  │  RenderStats         │
│  (data class)       │  │  (data class)        │
├─────────────────────┤  ├─────────────────────┤
│ enableRenderMonitor │  │ viewCount: Int       │
│ viewDrawThresholdMs │  │ maxDepth: Int        │
│   default: 16       │  │ activityName: String │
│ viewDepthThreshold  │  │ timestamp: Long      │
│   default: 10       │  └─────────────────────┘
│ viewCountThreshold  │
│   default: 300      │
│ detectOverdraw      │
│   default: false    │
│ maxStackTraceLength │
│   default: 4000     │
└─────────────────────┘
```

## View 树分析流程

```
onActivityCreated(activity)
       │
       ├── 延迟 1 秒（等待布局完成）
       │   └── mainHandler.postDelayed(1000ms)
       │
       └── inspectViewTree(activity)
              │
              ├── rootView = activity.window.decorView
              │
              ├── viewCount = 0, maxDepth = 0
              │
              ├── traverseViewTree(rootView, depth=0)
              │   ├── viewCount++
              │   ├── maxDepth = max(maxDepth, depth)
              │   └── if (view is ViewGroup)
              │       └── for (i in 0..view.childCount)
              │           └── traverseViewTree(view.getChildAt(i), depth+1)
              │
              ├── stats = RenderStats(viewCount, maxDepth, activityName, now)
              │
              ├── View 数量膨胀检测
              │   └── viewCount >= viewCountThreshold (300)
              │       → reportViewCountSpike(stats)
              │       → emit("view_count_spike", WARN)
              │
              └── 层级深度检测
                  └── maxDepth >= viewDepthThreshold (10)
                      → reportDeepHierarchy(stats)
                      → emit("deep_hierarchy", WARN)
```

## 检测维度

```
┌──────────────────────────────────────────────────┐
│              渲染检测维度                          │
├──────────────────────────────────────────────────┤
│                                                  │
│  1. View 数量膨胀                                │
│     单个 Activity 的 View 总数 >= 300            │
│     → 页面过于复杂， inflate/measure/layout 慢   │
│     → emit("view_count_spike", WARN)             │
│                                                  │
│  2. View 层级过深                                │
│     View 树最大深度 >= 10 层                     │
│     → measure/layout 递归开销大                  │
│     → emit("deep_hierarchy", WARN)               │
│                                                  │
│  3. 过度绘制检测 (预留)                          │
│     config.detectOverdraw = false (默认关闭)      │
│     → 需开启系统 "显示过度绘制" 才能检测         │
│                                                  │
│  事件字段:                                       │
│  {                                               │
│    viewCount,       // View 总数                 │
│    maxDepth,        // 最大深度                  │
│    activityName,    // Activity 类名             │
│    threshold        // 触发阈值                  │
│  }                                              │                                                  │
└──────────────────────────────────────────────────┘
```
