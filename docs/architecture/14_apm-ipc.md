# apm-ipc 模块架构

> IPC/Binder 监控：Binder 调用耗时 + 主线程阈值分级 + 聚合统计

---

## 类图

```
┌──────────────────────────────────────────┐
│            IpcModule                      │
│       (implements ApmModule)              │
├──────────────────────────────────────────┤
│ - config: IpcConfig                      │
│ - apmContext: ApmContext?                 │
│ - started: Boolean @Volatile             │
├──────────────────────────────────────────┤
│ + onInitialize(context)                  │
│ + onStart() / onStop()                   │
│ + onBinderCallComplete(                  │
│     interfaceName, methodName,           │
│     durationMs)                          │
└──────────────────────────────────────────┘

┌──────────────────────────────────┐
│         IpcConfig                │
├──────────────────────────────────┤
│ enableIpcMonitor: true           │
│ binderThresholdMs: 500           │
│ mainThreadBinderThresholdMs: 100 │
│ maxStackTraceLength: 4000        │
│ enableBinderHook: false          │
│ enableBinderAggregation: true    │
│ aggregationWindowSize: 50        │
└──────────────────────────────────┘
```

## 检测流程

```
onBinderCallComplete(interfaceName, methodName, durationMs)
       │
       ├── isMainThread = Looper.myLooper() == mainLooper
       │
       ├── 主线程 Binder 检测
       │   └── isMainThread && durationMs >= mainThreadBinderThresholdMs (100ms)
       │       → emit("slow_binder", ERROR)
       │
       └── 后台线程 Binder 检测
           └── !isMainThread && durationMs >= binderThresholdMs (500ms)
               → emit("slow_binder", WARN)
       │
       └── 通用字段:
           {
             interfaceName,
             methodName,
             durationMs,
             isMainThread,
             threshold
           }
```

## 聚合统计

```
┌──────────────────────────────────────────────────┐
│            Binder 调用聚合 (可选)                  │
├──────────────────────────────────────────────────┤
│                                                  │
│  每 aggregationWindowSize (默认 50) 次调用后      │
│  上报一次聚合数据:                                │
│  ├── 总调用次数                                   │
│  ├── 平均耗时                                     │
│  ├── 最大耗时                                     │
│  ├── 按接口聚合 (interfaceName → count/avg/max)  │
│  └── 主线程占比                                   │
│                                                  │
└──────────────────────────────────────────────────┘
```
