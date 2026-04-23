# apm-slow-method + apm-plugin 模块架构

> 慢方法检测：反射 Hook Looper + ASM 字节码插桩 + 栈采样 + 热点方法

---

## 双引擎架构

```
┌──────────────────────────────────────────────────────────────┐
│                   慢方法双引擎架构                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  引擎 1: Looper Printer Hook (运行时)                        │
│  ┌──────────────────────────────────────────────┐           │
│  │  Looper.setMessageLogging(printer)            │           │
│  │                                               │           │
│  │  >>>>> Dispatching to → 记录 dispatchStartMs │           │
│  │  <<<<< Finished to    → 计算耗时              │           │
│  │                                               │           │
│  │  if (durationMs >= threshold)                 │           │
│  │     → captureMainThreadStack()                │           │
│  │     → Apm.emit(ALERT)                         │           │
│  └──────────────────────────────────────────────┘           │
│                                                              │
│  引擎 2: ASM 字节码插桩 (编译期)                             │
│  ┌──────────────────────────────────────────────┐           │
│  │  apm-plugin (AGP instrumentation)             │           │
│  │  ┌────────────────────────────────────┐      │           │
│  │  │ ApmClassTransformer (ASM)           │      │           │
│  │  │                                     │      │           │
│  │  │ 原始字节码:                         │      │           │
│  │  │   method foo() {                    │      │           │
│  │  │     // business logic               │      │           │
│  │  │   }                                 │      │           │
│  │  │                                     │      │           │
│  │  │ 插桩后字节码:                       │      │           │
│  │  │   method foo() {                    │      │           │
│  │  │     Tracer.methodEnter("Foo#foo")   │      │           │
│  │  │     try {                           │      │           │
│  │  │       // business logic             │      │           │
│  │  │     } finally {                     │      │           │
│  │  │       Tracer.methodExit("Foo#foo")  │      │           │
│  │  │     }                               │      │           │
│  │  │   }                                 │      │           │
│  │  └────────────────────────────────────┘      │           │
│  └──────────────────────────────────────────────┘           │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## 类图

```
┌──────────────────────────────────────────────────────────┐
│              SlowMethodModule                             │
│          (implements ApmModule)                           │
├──────────────────────────────────────────────────────────┤
│ - apmContext: ApmContext?                                 │
│ - config: SlowMethodConfig                               │
│ - originPrinter: Printer?                                │
│ - monitoring: Boolean @Volatile                          │
│ - dispatchStartTime: Long @Volatile                      │
│ - samplingProfiler: StackSamplingProfiler                │
├──────────────────────────────────────────────────────────┤
│ + onInitialize(context)                                  │
│ + onStart()                                              │
│   ├── 反射获取 Looper.mLogging 字段                      │
│   ├── 保存原始 Printer (originPrinter)                   │
│   └── 设置自定义 Printer                                 │
│ + onStop()                                               │
│ + reportSlowMethod(durationMs, logMsg)                   │
│ + onSamplingResult(topMethods, sampleCount)              │
│ + captureMainThreadStack(): String                       │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│           ApmSlowMethodTracer «object»                    │
├──────────────────────────────────────────────────────────┤
│ - thresholdMs: Long = 300                                │
│ - enabled: Boolean @Volatile                             │
│ - enterTimes: ThreadLocal<Stack<Pair<String, Long>>>     │
│ - hotMethods: ConcurrentHashMap<String, HotMethodInfo>   │
│ - maxHotMethods: Int = 100                               │
├──────────────────────────────────────────────────────────┤
│ + init(thresholdMs, maxHotMethods)                       │
│ + methodEnter(methodSignature)  @JvmStatic              │
│ + methodExit(methodSignature)   @JvmStatic              │
│ + getHotMethods(): List<HotMethodInfo>                   │
│ + clearHotMethods()                                      │
└──────────────────────────────────────────────────────────┘

┌─────────────────────────────┐
│   HotMethodInfo              │
├─────────────────────────────┤
│ methodSignature: String      │
│ hitCount: AtomicInteger      │
│ totalDurationMs: AtomicLong  │
└─────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│             StackSamplingProfiler                         │
├──────────────────────────────────────────────────────────┤
│ - samplingThread: HandlerThread                          │
│ - samplingHandler: Handler                               │
│ - hotMethods: ConcurrentHashMap<String, AtomicInteger>   │
│ - sampleCount: Int @Volatile                             │
│ - sampling: Boolean @Volatile                            │
│ - onSamplingComplete: ((List, Int) -> Unit)?             │
├──────────────────────────────────────────────────────────┤
│ + startSampling()                                        │
│ + stopSampling()                                         │
│ + captureMainThreadStack(): String                       │
│ + shouldSkip(signature): Boolean                         │
│ + destroy()                                              │
└──────────────────────────────────────────────────────────┘
```

## Looper Printer Hook 流程

```
┌────────────────────────────────────────────────────────┐
│             Looper Printer Hook 检测流程                │
├────────────────────────────────────────────────────────┤
│                                                        │
│  自定义 Printer.print(what):                           │
│                                                        │
│  if (what.startsWith(">>>>> Dispatching to"))          │
│       │                                                │
│       ├── dispatchStartTime = SystemClock.elapsedReal() │
│       └── samplingProfiler.startSampling()             │
│            └── 每 10ms 采样主线程堆栈                    │
│                                                        │
│  if (what.startsWith("<<<<< Finished to"))             │
│       │                                                │
│       ├── durationMs = elapsed - dispatchStartTime     │
│       ├── samplingProfiler.stopSampling()              │
│       │                                                │
│       ├── if (durationMs >= threshold)                 │
│       │   ├── stackTrace = captureMainThreadStack()    │
│       │   └── reportSlowMethod(durationMs, what)       │
│       │       └── Apm.emit(                            │
│       │             module = "slow_method",            │
│       │             name = "slow_method_detected",     │
│       │             severity =                         │
│       │               >= 800ms ? ERROR : WARN,         │
│       │             fields = {                         │
│       │               durationMs,                      │
│       │               isSevere,                        │
│       │               looperMsg,                       │
│       │               mainThreadStack                  │
│       │             }                                  │
│       │           )                                    │
│       │                                                │
│       └── originPrinter?.print(what)                   │
│           └── 传递给原始 Printer                       │
│                                                        │
└────────────────────────────────────────────────────────┘
```

## ASM 插桩流程

```
┌────────────────────────────────────────────────────────────┐
│          AGP instrumentation + ASM 编译期插桩                │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ApmSlowMethodPlugin.apply(project)                        │
│       │                                                    │
│       ├── 注册 Extension (apmSlowMethod)                   │
│       │   ├── enabled: Boolean = true                      │
│       │   └── excludePackages: List<String>  (排除的包)    │
│       │                                                    │
│       └── 注册 AsmClassVisitorFactory                      │
│            │                                               │
│            ▼                                               │
│  variant.instrumentation.transformClassesWith(...)         │
│       │                                                    │
│       ├── InstrumentationScope.PROJECT                     │
│       ├── isInstrumentable(classData)                      │
│       └── createClassVisitor(...)                          │
│            │                                               │
│            ▼                                               │
│  ApmClassTransformer.createClassVisitor(...)               │
│       │                                                    │
│       ├── isInstrumentable(className, excludePackages)?    │
│       │   ├── 插件 enabled?                               │
│       │   ├── 匹配 excludePackages?                       │
│       │   └── 排除 Android/Java/Kotlin 框架类              │
│       │                                                    │
│       └── ASM 转换                                         │
│            ├── AGP 提供 ClassVisitor 链                    │
│            ├── FramesComputationMode                       │
│            │   COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS    │
│            ├── ApmClassVisitor(nextVisitor)                │
│            │   └── ApmMethodVisitor(AdviceAdapter)        │
│            │       ├── onMethodEnter()                     │
│            │       │   └── mv.visitLdcInsn(signature)     │
│            │       │   └── mv.visitMethodInsn(             │
│            │       │         INVOKESTATIC,                 │
│            │       │         "ApmSlowMethodTracer",        │
│            │       │         "methodEnter",                │
│            │       │         "(Ljava/lang/String;)V")      │
│            │       │                                       │
│            │       └── onMethodExit(opcode)                │
│            │           └── mv.visitLdcInsn(signature)     │
│            │           └── mv.visitMethodInsn(             │
│            │                 INVOKESTATIC,                 │
│            │                 "ApmSlowMethodTracer",        │
│            │                 "methodExit",                 │
│            │                 "(Ljava/lang/String;)V")      │
│            │                                               │
│            └── return writer.toByteArray()                 │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

## 当前仓库接线状态

- `settings.gradle.kts` 通过 `pluginManagement { includeBuild("apm-plugin") }` 解析本地 Gradle 插件。
- `apm-sample-app/build.gradle.kts` 已应用 `id("com.apm.slow-method")`，sample 构建会实际执行 ASM 插桩链路。
- 运行时 tracer 目标类为 `com.apm.slowmethod.ApmSlowMethodTracer`，已与插件注入目标保持一致。
- 插件扩展只控制编译期插桩范围；慢方法阈值、采样和上报策略统一由运行时 `SlowMethodConfig` 控制。
- 插件已迁移到 AGP instrumentation API，`gradle.properties` 不再需要 legacy Transform 兼容开关。

## ApmSlowMethodTracer 运行时流程

```
┌──────────────────────────────────────────────────────────┐
│             Tracer 运行时方法追踪流程                      │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  methodEnter("com/example/Foo#bar")                     │
│       │                                                  │
│       └── stack = ThreadLocal.getOrCreate()              │
│           └── stack.push(("Foo#bar", elapsedRealNanos()))│
│                                                          │
│  methodExit("com/example/Foo#bar")                      │
│       │                                                  │
│       ├── stack = ThreadLocal.get()                      │
│       ├── if (empty || top != signature) → return        │
│       ├── stack.pop()                                    │
│       │                                                  │
│       ├── durationNs = now - enterTime                   │
│       ├── durationMs = durationNs / 1_000_000            │
│       │                                                  │
│       ├── updateHotMethod(signature, durationMs)         │
│       │   └── hotMethods.getOrPut(signature)             │
│       │       ├── hitCount.incrementAndGet()             │
│       │       └── totalDurationMs.addAndGet(durationMs)  │
│       │                                                  │
│       └── if (durationMs >= threshold)                   │
│           └── reportSlowMethod(signature, durationMs)    │
│               └── Apm.emit(                              │
│                     module = "slow_method",               │
│                     name = "slow_method_instrumented",    │
│                     severity =                            │
│                       >= 800ms ? ERROR : WARN,            │
│                     fields = {                            │
│                       method, durationMs, threshold,      │
│                       detectionType = "asm"               │
│                     }                                     │
│                   )                                       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```
