# AndroidAPM 项目交接快照

> 同步日期：2026-07-10｜分支：`develop`｜当前 tip 请执行 `git log --oneline -n 10`

## 结论

当前仓库是已成型的 Android APM 客户端 SDK：15 个监控模块、4 个基础模块、2 个扩展模块、一个示例应用、一个 ASM 插件 included build 和一个 convention-plugin included build。

端上事件管线、SQLite durable outbox、批量上传和主要模块已有测试与本地构建证明；生产 Collector、查询/告警后台、事件幂等、并发 upload lease、外部 Maven 发布和真机长稳报告仍未完成。

## 事实源

1. 当前源码/构建文件
2. 当前测试与构建输出
3. `AGENTS.md`
4. `docs/Android_APM_项目文档.md`
5. `docs/architecture/00_整体架构.md`
6. 对应模块文档

不要从旧提交号或模型记忆推导当前状态。

## 当前工程清单

| 项目 | 数量/版本 |
|---|---|
| root subproject | 22 |
| included build | 2：`apm-plugin`, `build-logic` |
| 构建单元 | 24 |
| 基础模块 | 4 |
| 监控模块 | 15 |
| 扩展模块 | 2 |
| 主源码 | 128：123 Kotlin + 4 C + 1 proto |
| 测试文件 | 63 |
| JDK | 21 |
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.2.21 |
| Android | compileSdk 34 / minSdk 24 / targetSdk 34 |
| JVM bytecode | Java 11 |

最新 runtime 实现提交（文档同步前）为：

```text
3c27ff9 Refactor: centralize SDK threads via ApmExecutors with two-tier priority policy
```

之后的提交可能是样式、文档或仓库跟踪调整；使用 Git 历史判断实际 tip。

## 30 秒运行时理解

```text
Apm.init
  -> create logger/store/uploader/limiter/dispatcher
  -> optional coordinator/self-monitor
  -> start registered modules

Apm.emit
  -> bounded queue 2048
  -> aggregate/rate-limit/sanitize when enabled
  -> batch transaction, up to 32 events
  -> SQLite outbox 50K
  -> persistent worker
  -> uploader
  -> external collector
```

关键语义：

- Crash 可同步落盘，不同步做网络。
- 成功上传才 delete；失败 markRetry。
- retry ≥ 10 或 age > 7 天清理。
- 网络完成不确定时可能重复，当前无 eventId/idempotency。
- 当前 durable worker 是单 worker 所有者模型，无多 worker claim/lease。
- FileEventStore 是非 durable 兼容路径。

## 接入现实

注册即可工作的能力：Java Crash、ANR 双通道、Memory 周期采样、Launch 生命周期、FPS、Thread/GC 周期采样、Render View 树。

需要宿主接线：

- Network：OkHttp Interceptor/EventListener 或手动 callback
- SQLite：`ApmSQLiteDatabase` 或 `onSqlExecuted`
- IPC：`onBinderCallComplete`
- WebView：页面/JS/资源 callback
- Battery：WakeLock/GPS/Alarm callback
- IO：stream wrapper；Native path 还依赖 xhook
- Slow Method：宿主应用 Gradle ASM 插件

当前没有完整实现的声明型能力：通用 Binder hook、WebView 自动注册、线程池 backlog、Render overdraw/draw-time detector。

## 默认配置注意

- endpoint 空：Logcat，不会上送生产服务
- SQLite durable storage：开启
- PII sanitization：关闭
- aggregation：关闭
- multi-process coordination：关闭
- self-monitor/auto-throttle：开启
- native crash：关闭
- Hprof/fork dump：关闭

生产接入必须明确 endpoint/鉴权、隐私规则、远程配置、采样限流、进程策略和服务端幂等。

## 验证

2026-07-10 已在 JDK 21.0.11 对完成文档同步的当前 tip 执行：

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

全部通过。XML 报告合计 68 个套件、479 个测试，0 failures / 0 errors / 0 skipped；生成 21 份 lint HTML、4,570,504 字节的 sample unsigned Release APK，以及 Maven Local 中 20 个 AAR、22 个 JAR、21 个 POM。独立 consumer 已从本地制品清理重建成功。

## 新电脑接手

1. 克隆仓库并切到 `develop`。
2. 确认 `JAVA_HOME` 指向 JDK 21；AGP 8.13.2 不能运行在 JDK 11。
3. 安装 Android SDK 34 和项目需要的 NDK/CMake。
4. 按 AGENTS 读序阅读。
5. 执行：

```powershell
git status --short --branch
git log --oneline -n 10
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug
./gradlew.bat -p apm-plugin test
```

6. 发布相关变更再执行：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug
```

## 后续优先级

### P0

- 生产 Collector、鉴权、租户、查询聚合、告警与 Dashboard
- eventId/idempotency 与重复处理协议

### P1

- 并发 upload claim/lease/expiry
- 真机 Native/ANR/Crash/IO 验证和符号化链
- 清理或补全没有运行时消费者的配置开关
- typed fields/schema 演进

### P2

- 真机 soak、功耗、磁盘、主线程开销基准
- Maven Central/外部私服发布
- 当前 tip 的 Release/lint/publish/smoke 周期验证

## Git 与文档策略

- `docs/` 纳入 Git 并随代码同步。
- `.workbuddy/`、`.github/`、`.claude/` 保持忽略。
- 不在交接文档中写“当前最新文档提交”；使用 `git log`。
- 代码/架构/构建/测试变化后同步 AGENTS、项目文档和对应模块文档。
- 对外能力或接入方式变化后同步 README。
