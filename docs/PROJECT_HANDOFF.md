# AndroidAPM 项目交接快照

> 同步日期：2026-07-11｜分支：`develop`｜当前 tip 请执行 `git log --oneline -n 10`

## 结论

当前仓库是已成型的 Android APM 客户端 SDK：15 个监控模块、4 个基础模块、2 个扩展模块、一个示例应用、一个非发布 benchmark 模块、一个 ASM 插件 included build 和一个 convention-plugin included build。

端上事件管线、稳定 eventId、SQLite durable outbox、并发 upload lease、批量上传、显式监控接入和 benchmark harness 已有测试与本地构建证明。生产 Collector、查询/告警后台、服务端幂等、外部 Maven 发布和真机长稳数值属于外部建设，统一见 `docs/云端待建设清单.md`。

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
| root subproject | 23 |
| included build | 2：`apm-plugin`, `build-logic` |
| 构建单元 | 25 |
| 基础模块 | 4 |
| 监控模块 | 15 |
| 扩展模块 | 2 |
| 主源码 | 141：136 Kotlin + 4 C + 1 proto |
| 测试/benchmark 文件 | 76 |
| JDK | 21 |
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.2.21 |
| Android | compileSdk 34 / minSdk 24 / targetSdk 34 |
| JVM bytecode | Java 11 |

最新 runtime 实现提交（文档同步前）为：

```text
b423ad7 Refactor: Make APM lifecycle failure-safe
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
- 每个事件的稳定 `eventId` 贯穿所有 wire/storage/IPC 格式。
- Worker 先原子 claim；只有 owner 可 ACK/失败释放，租约过期可重领。
- retry ≥ 10 或 age > 7 天清理。
- 网络完成不确定时仍可能重传；服务端必须按 `eventId` 幂等。
- FileEventStore 是非 durable 兼容路径。
- SDK 自诊断使用条数 + 字节双预算内存环/队列和按进程隔离的 app-private 滚动文件，不经过 dispatcher/outbox/uploader；支持全进程聚合导出和 executor 异步读取。

## 接入现实

注册即可工作的能力：Java Crash、ANR 双通道、Memory 周期采样、Launch 生命周期、FPS、Thread/GC 周期采样、Render View 树与 FrameMetrics。

需要宿主接线：

- Network：OkHttp Interceptor/EventListener 或手动 callback
- SQLite：`ApmSQLiteDatabase` 或 `onSqlExecuted`
- IPC：`traceBinderCall` 或 `onBinderCallComplete`
- WebView：对指定实例 `install/uninstall`、delegate wrapper 或页面/JS/资源 callback
- Thread Pool：显式 `registerThreadPool(name, ThreadPoolExecutor)`
- Battery：WakeLock/GPS/Alarm callback
- IO：stream wrapper；Native path 还依赖 xhook
- Slow Method：宿主应用 Gradle ASM 插件

不宣称无法由公共 API 支撑的能力：通用 Binder hidden hook、进程级 WebView 自动接管、通用线程 leak 判断和 GPU overdraw 计数。对应旧字段已弃用并默认关闭，真实能力使用显式 API；FrameMetrics 和注册线程池 backlog 已实现。

## 默认配置注意

- endpoint 空：Logcat，不会上送生产服务
- SQLite durable storage：开启
- PII sanitization：关闭
- aggregation：关闭
- multi-process coordination：关闭
- self-monitor/auto-throttle：开启
- self-diagnostics：开启；200 条 / 4 MiB 内存、256 条 / 4 MiB 队列、每进程 3 × 512 KiB 文件；不自动上传
- native crash：关闭
- Hprof/fork dump：关闭

生产接入必须明确 endpoint/鉴权、隐私规则、远程配置、采样限流、进程策略和服务端幂等。

## 验证

2026-07-11 已在 JDK 21.0.11 对完成 SDK 自诊断加固的当前 tip 执行：

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

全部通过。XML 报告合计 75 个套件、514 个测试，0 failures / 0 errors / 0 skipped；生成 21 份 lint HTML、4,606,048 字节的 sample unsigned Release APK，以及 Maven Local 中 20 个 AAR、22 个 JAR、21 个 POM。独立 consumer 已从本地制品清理重建成功。Android SDK 可用，但本次 `adb devices` 没有连接目标，因此真机多进程矩阵仍属于外部验证项，未计入本地完成证明。

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
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
```

6. 发布相关变更再执行：

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug
```

## 后续优先级

客户端代码可独立完成的既定缺口已经收口。后续事项均需要 Collector、平台凭据、CI 管理员、符号服务或真实设备，按 [`云端待建设清单.md`](云端待建设清单.md) 的 P0/P1/P2、协议和验收条件推进。typed fields 也必须与 Collector 做版本化 schema 演进，不能在客户端静默改变 wire 类型。

## Git 与文档策略

- `docs/` 纳入 Git 并随代码同步。
- `.workbuddy/`、`.github/`、`.claude/` 保持忽略。
- 不在交接文档中写“当前最新文档提交”；使用 `git log`。
- 代码/架构/构建/测试变化后同步 AGENTS、项目文档和对应模块文档。
- 对外能力或接入方式变化后同步 README。
