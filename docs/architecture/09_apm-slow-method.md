# apm-slow-method 与 apm-plugin

> 同步日期：2026-11-26｜运行时模块：`slow_method`

## 三条观测路径

### Looper Printer Hook 与阻塞期合并看门狗

`SlowMethodModule` 通过反射读取并保留既有 `Looper.mLogging`，再通过公开 `setMessageLogging` 安装包装器，以递增分发序号（`0` 表示空闲，不依赖可能为负的 `System.nanoTime()` 起点）和单调时钟计算主线程消息耗时。高频短消息仅写内存序号与时间戳，每个阈值窗口最多向 `apm-stack-sampler` 线程投递一次合并看门狗（空闲屏幕自动解除武装、零周期唤醒）；当单次消息阻塞达到 `thresholdMs` 且尚未 `Finished` 时，看门狗在阻塞现场抓取真实主线程调用栈（二次校验消息序号未切换，绝不把事后 `Looper.loop` 栈混入 `mainThreadStack`），并立即触发热点采样。若堆栈抓取与栈采样均关闭，则不创建后台采样线程。

### Stack sampling

慢消息阻塞达到阈值（或极端线程调度延迟下在 `Finished` 兜底确认）后，`StackSamplingProfiler` 在 measurement-priority daemon `HandlerThread` 中按 10ms 间隔、5s 窗口采样主线程 stack，过滤系统框架及 `com.apm.slowmethod.*` 自身包装帧后统计 top 10 热点方法；采样停止仅取消采样自身任务，不误删同线程上的合并看门狗。

### ASM instrumentation

宿主必须应用 `com.apm.slow-method`。插件在 `apply()` 阶段通过 `pluginManager.withPlugin` 延迟注册：先应用 `com.apm.slow-method` 再应用 Android 插件（或相反）都生效，声明顺序不影响插桩；非 Android module 应用插件时在配置评估后给出 warn 诊断。

```text
AGP instrumentation scope PROJECT
  -> AsmClassVisitorFactory
  -> skip excluded/constructor/static-init/synthetic/abstract/native
  -> methodEnter(class#method+descriptor)
  -> normal return: methodExit
  -> propagated exception: catch-all handler -> methodExit -> ATHROW
```

只插桩当前 project，默认排除 Android/AndroidX/Kotlin/Java/APM tracer 包。排除项为包边界语义：不带尾点的 `com.example` 只匹配其包及子包，不会误排 `com.exampleevil.*`。AGP 重算被插桩方法 frames。

独立字节码转换路径（公开 API `transformJar`/`transformDirectory`）保留输入 class 的精确 StackMapFrame，只用 `COMPUTE_MAXS` 重算最大栈，并为新增 catch-all handler 写入不读取 locals 的最小 Throwable frame。该路径不再用插件 ClassLoader 解析宿主类层次，也不会把未知兄弟类型一律合并成 `java/lang/Object`；后者会在原代码随后按共同父类调用方法时生成 JVM/ART 无法验证的字节码。Windows 目录路径的反斜杠会先归一化为点分类名，再执行与 Jar/AGP 一致的包边界排除。

## 运行时 tracer

`ApmSlowMethodTracer` 使用 ThreadLocal stack 配对 enter/exit，默认阈值 300ms，hot-method map 上限 100。事件：`slow_method_instrumented`。

Module 事件：`slow_method_detected`, `hot_methods_detected`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| slow/severe | 300ms / 800ms |
| include stack | 开，最大 4000 chars |
| stack sampling | 开 |
| sample interval/window | 10ms / 5s |
| top methods | 10 |

## 线程与开销

Looper hook 回调在主线程，只做计时/触发；stack sampling 在独立 measurement thread。ASM 对每个被插桩方法增加 enter/exit 调用，必须通过包过滤和灰度控制开销。

## 边界

- 注册 `SlowMethodModule` 不会自动让所有方法 ASM 化；Gradle 插件是独立接线。
- Looper hook 只能定位 message dispatch 粒度。
- stack sampling 是统计近似，不保证命中真实最慢方法。
- 插件 transform 的工具方法在无法恢复的字节码损坏时仍返回原 class；有效输入的既有 frames 会保留，新增 handler frame 不依赖宿主 classpath，AGP visitor 路径仍由构建系统重算被插桩方法 frames。

## 测试

Module/config/tracer 测试覆盖运行时；`apm-plugin` 独立测试验证过滤、方法签名、正常返回、显式/传播异常的 exit 平衡，以及插件延迟注册（无 Android 插件时安全）、不可解析宿主兄弟类型合并后仍能由 JVM 加载执行、Windows 路径归一化和排除包名的包边界语义。真实 sample 还以慢方法插件先于 Android 插件的声明顺序完成 Debug ASM transformation。

## 时间语义

Looper、采样和 ASM tracer 的方法 duration 统一通过 `ApmClock` 单调时间计算；事件 timestamp 保持 epoch，系统校时不改变慢方法判定。
