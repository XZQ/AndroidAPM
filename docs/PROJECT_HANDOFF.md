# AndroidAPM 项目交接快照

> 同步日期：2026-11-22｜分支：`develop`｜当前 tip 请执行 `git log --oneline -n 10`

## 结论

当前构建使用 JDK 21 和 AGP 9.5.2。近期变更与验收证据分别见 [变更历史](CHANGELOG.md)和[历史验证记录](HISTORICAL_VERIFICATION.md)。

端上事件管线、单依赖 `apm-bundle` 分发、strict production profile/显式 consent/撤回清理、V2 batch-declared 兼容和 V3 occurrence-bound typed/resource/batch/size/exact-ACK 契约、Crash/ANR 同步 critical hand-off、按 drop reason/priority 的损失证据、稳定 eventId、durable codec V4 与 V1/V2/V3 读取、SQLite durable outbox、并发 upload lease、dispatcher/IPC/SQLite 跨层条数与字节预算、动态短期鉴权、签名远程配置/kill switch/采样/限流/endpoint、优先级感知背压与单模块高水位隔离、业务上下文同步契约/异步 LKG 缓存、带迟滞恢复的 AutoThrottle、默认 PII 保护、配置/payload/occurrence 快照、批量上传、显式监控接入、25 坐标发布候选/Gradle 插件 marker/依赖 checksum/制品 manifest/SPDX SBOM，以及固定 time/allocation 预算与 fail-closed verifier 已有测试和本地构建证明。生产 PostgreSQL/TLS/SigNoz、真实通知/符号化工具、外部 Maven staging/promotion、云端 runner 和真机长稳数值属于外部建设，统一由独立 `AndroidAPM-Server` 仓库的 `docs/云端待建设清单.md` 管理。

生产可靠性以宿主安全优先：dispatcher 单事件 recoverable failure 不终止共享 worker，fatal VM error 不伪装成 drop/retry；共享入口同时受 2048 条和默认 8 MiB 估算字节预算约束，75% 高水位后单一 NORMAL/LOW 模块默认最多占总队列容量 50%，HIGH/CRITICAL 不受该隔离门禁影响，并可淘汰足够数量的旧低优事件满足双预算。dispatcher 仍是单 worker，该措施隔离入口容量而非增加并行吞吐。IPC 另有 4 MiB pending、256 KiB raw event、1 MiB file、16 MiB ready-directory 预算；outbox stale 删除计数不降到 0 以下，Retry-After 秒数先饱和换算且最终等待上限 60 秒，自定义同步 uploader 的阻塞终止由宿主负责；diagnostics 显式导出失败返回结果数据而不抛回支持流程。

2026-08-24 审查修复补齐两个所有权/并发边界：SQLite 除行数缓存外持久跟踪 `sqlite_sequence` 插入水位，因此另一 store 删除小行、插入大行且总行数不变时仍会重建 payload 字节真值；容量淘汰从事务内真值计算最终缓存，不从可能漂移的旧缓存扣减。公开 `PiiSanitizer.sanitize` 对 clean mutable map 也返回隔离快照，dispatcher 仅对入队时已冻结所有权的事件使用内部共享快路径；首个变化值不会被自定义规则执行两次，超长字段名和动态 module/name 只计算、不进入长期缓存。

2026-09-07 审查修复已覆盖上传取消/退出证据、不可被 signal 打断的退避、V3 不兼容行单独处置、不可变字段快照/未知对象拒绝、邮箱线性扫描、保留发生身份与维度的数值聚合、FrameMetrics idle 误报和 OkHttp route 重试最终状态。升级策略明确：无 occurrence 的历史行在内置 SQLite + strict V3 路径按 owner discard 并计数，不补当前身份、不降级协议；自定义旧 store 保留拒绝行自己的 retry/TTL 策略。详见 `docs/Android_APM_项目文档.md` 的本轮修复说明。

本轮最终验证（2026-09-07，JDK 17.0.14）：`python tools/verify_ci.py` 完整通过；强制重跑 Android 101 suites / 700 tests、model 5 suites / 57 tests、独立 plugin 1 suite / 18 tests，全部 0 failures/errors/skipped。根与 plugin API check、24 份基线、4 组严格依赖元数据、5 项发布候选 verifier 测试、41 项 benchmark/device-lab host 测试通过；本地候选验证 25 坐标 / 22 AAR / 26 JAR / 25 POM，独立 Maven consumer 使用 `--refresh-dependencies` 完成 clean assembleDebug 和 ASM transformation。core/storage/uploader/network/fps lint 均无问题，sample debug、benchmark release 与 AndroidTest Kotlin 编译通过。`verify_collector_e2e.py` 对参考服务端实际工作树完成真实 V2/V3 Gzip HTTP、exact ACK、typed/occurrence 持久化、installation HMAC 和重放去重验证。候选来自尚未提交的工作树，manifest 的 sourceDirty=true；这不是外部 Maven 发布或真机/生产 PostgreSQL/TLS/SigNoz 验收。

## 事实源

2026-09-12 八项审查修复已分别验证；本轮新增历史退出语义：历史 app_exit 保留系统记录的退出时间/进程，使用退出前写入平台摘要的完整 occurrence，不绑定当前 release。摘要最多 128 字节，五字段不能完整容纳或历史摘要不可验证时，strict V3 在入队前明确丢弃并计入 HISTORICAL_OCCURRENCE_UNAVAILABLE，同时推进处理水位，不伪造身份或降级 V2。新增 CrashModule(CrashConfig, Boolean) overload 可关闭平台摘要写入/清理；原 CrashConfig constructor/copy/component 保持。模块 stop/撤回仅尝试清当前进程摘要，不能删除 OS 已保留的退出记录。后台 trace 读取与最终交接受会话取消门禁约束，原 context 不会进入新 init。 完整源码与逐项验证见主项目文档；本轮整合门禁已通过，结果见本文开头。

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
| root subproject | 25 |
| included build | 2：`apm-plugin`, `build-logic` |
| 构建单元 | 27 |
| 基础模块 | 5 |
| 监控模块 | 15 |
| 扩展模块 | 2 |
| 分发 Bundle | 1：`apm-bundle` |
| 主源码 | 170：165 Kotlin + 4 C + 1 proto |
| 测试/benchmark 源文件 | 130（Gradle 的 `src/test` 与 `src/androidTest`） |
| Gradle daemon | JDK 21 |
| Java toolchain | 21 |
| Gradle / AGP / Kotlin | 9.6.0 / 9.5.2 / 2.2.21 |
| Android | compileSdk 36 / minSdk 24 / targetSdk 34 |
| JVM bytecode | Java 21 |

主构建、两个 included build 和独立 Maven consumer 都使用 JDK 21 运行 Gradle daemon 和编译测试。Java/Kotlin 发布制品使用 Java 21 字节码。

远程配置里程碑的源码和文档位于同一交付提交；不要在交接文档内固化会立即过期的“最新提交号”，使用 Git 历史判断实际 tip。

## 30 秒运行时理解

```text
Apm.init
  -> create logger/store/uploader/limiter/dispatcher
  -> optional signed config/coordinator/self-monitor
  -> start registered modules

Apm.emit
  -> caller timestamp/thread/payload + synchronous or cached bizContext snapshot
  -> priority-aware bounded queue 2048 events / 8 MiB estimated retained bytes
     -> default 75% high-water / 50% per-module NORMAL/LOW share
  -> signed sampling/aggregate/dynamic rate-limit/default sanitize
  -> batch transaction, up to 32 events
  -> SQLite outbox 50K / 64 MiB live payload / 256 KiB per event
  -> persistent worker
  -> uploader
  -> external collector
```

关键语义：

- Crash 可同步落盘，不同步做网络。
- 每个事件的稳定 `eventId` 贯穿所有 wire/storage/IPC 格式。
- Worker 先原子 claim；只有 owner 可 ACK/失败释放，租约过期可重领。
- `maxRetries` 是首次尝试后的重试次数；失败达到 `maxRetries + 1` 后立即清理，age > 7 天也清理。
- 网络完成不确定时仍可能重传；服务端必须按 `eventId` 幂等。
- FileEventStore 是非 durable 兼容路径。
- Crash/ANR 使用 `Apm.emitCriticalSync` 绕过共享 queue/sampling/aggregation/rate limit，较低 priority 自动提升为 CRITICAL；成功表示完整事件同步到 SQLite 或 critical IPC 文件，不在现场线程执行网络 IO。上传进程对 CRITICAL IPC 继续同步落 store，只有接受后删除 ready 文件；false/recoverable failure/no consumer 保留整文件重试，已发布 `.ipc` 不按年龄先删，16 MiB directory budget 继续限界。IPC 同步拒绝返回准确的 event/file/directory budget reason。
- SDK 自诊断使用条数 + 字节双预算内存环/队列和按进程隔离的 app-private 滚动文件，不经过 dispatcher/outbox/uploader；每个 `sdk_health` 先写独立数值摘要，再以 HIGH 优先级尝试事件通道。每个 loss 同时记录稳定 reason 与 priority，兼容聚合结果显式进入 `UNATTRIBUTED`；SQLite capacity/prune 保留精确 priority counts。`dispatcherModuleIsolationDropCount` 单列模块隔离丢弃且同时计入总 drop，`queueBytes` 与 `queueSize` 同时暴露入口压力；支持全进程聚合导出和 executor 异步读取。
- `ApmDiagnostics.hostIntegrationSnapshot()` 以不访问文件、与 journal 开关无关的方式报告 Network/SQLite/IPC/WebView/线程池/Battery/IO 显式接线的模块状态、活跃 registration、会话内信号数和最后时间。它只保留固定枚举/计数/时间戳；`NO_RUNTIME_EVIDENCE` 是“本会话尚未发生”，不是静态失败结论。停止清零活跃 registration，重新 init 清除旧证据。

## 接入现实

注册即可工作的能力：Java Crash、ANR 双通道、Memory 周期采样、Launch 生命周期、FPS、Thread/GC 周期采样、Render View 树与 FrameMetrics。

需要宿主接线：

- Network：OkHttp Interceptor/EventListener、显式 `traceHttpUrlConnection` 或手动 callback
- HttpURLConnection helper：读取 `responseCode` 执行，不读正文、不 disconnect、不提供伪分阶段数据
- SQLite：`ApmSQLiteDatabase` 或 `onSqlExecuted`
- IPC：`traceBinderCall` 或 `onBinderCallComplete`
- WebView：对指定实例 `install/uninstall`、delegate wrapper 或页面/JS/资源 callback
- Thread Pool：显式 `registerThreadPool(name, ThreadPoolExecutor)`
- Battery：WakeLock/GPS/Alarm callback
- IO：stream wrapper；Native path 还依赖 xhook
- Slow Method：宿主应用 Gradle ASM 插件

不宣称无法由公共 API 支撑的能力：通用 Binder hidden hook、进程级 WebView 自动接管、通用线程 leak 判断和 GPU overdraw 计数。对应旧字段已弃用并默认关闭，真实能力使用显式 API；FrameMetrics 和注册线程池 backlog 已实现。

接入自检应在代表性 Network/SQLite/IPC/WebView/Battery/IO 流程实际执行后读取 `ApmDiagnostics.hostIntegrationSnapshot()`；WebView 与线程池可先凭当前 registration 证明安装，其他 `NO_RUNTIME_EVIDENCE` 项需要结合“该流程本次是否发生”判断，不能单靠一次冷启动快照作为发布门禁。

初始化方式也必须明确二选一：手动 `Apm.init` 的宿主从合并 manifest 移除 `ApmInitProvider`；自动模式保留 Provider 并配置 `com.apm.config_class`。Sample 使用手动模式，并为 IO wrapper、`ApmSQLiteDatabase`、WebView install、IPC trace、线程池注册及 Battery 回调提供可运行入口。

完整能力宿主可只依赖 `com.apm:apm-bundle:0.1.0`；该制品通过 POM 传递暴露全部 22 个运行时模块，但不会自动初始化、注册模块或应用 `com.apm.slow-method`。体积敏感宿主仍应按需选择细粒度模块。

## 默认配置注意

- runtime profile：默认 `COMPATIBILITY` 保持源兼容；生产显式选择 `PRODUCTION_STRICT`
- consent：默认 `UNSPECIFIED`；strict 初始化必须显式 `GRANTED`，`DENIED` 在所有 profile 下拒绝
- endpoint 空/未知 scheme：仅兼容模式安全确认后丢弃；strict 要求 HTTPS 或非 Logcat 自定义 uploader
- serialization：默认 legacy `LINE_PROTOCOL`；strict built-in HTTP 要求 `PROTOBUF_ENVELOPE_V3`
- V3 occurrence：三参数 `Apm.init` 提供完整 occurrence；当前自动 Provider 仅调用二参数 overload，不支持 V3
- V2/V3 resource：service/version/environment/匿名 installation 四项固定字段；strict 必须完整且有界，但版本归因以 V3 occurrence 为准
- V2/V3 request bytes：gzip 前默认 1 MiB、绝对上限 4 MiB；按实际编码拆分，每个物理 batch 独立 exact ACK
- dynamic HTTP endpoint：默认关闭；开启也只接受无凭据 HTTPS URL
- static HTTP headers / per-request header provider：默认空；长期密钥不能打进 APK
- SQLite durable storage：开启
- durable payload：单事件软上限 256 KiB；活跃 payload 逻辑预算 64 MiB，超限按低优先级旧事件淘汰并计入自监控
- dispatcher：2048 条 + 8 MiB 估算保留内存双预算；HIGH/CRITICAL 可淘汰足够数量的旧低优事件
- multi-process IPC：lock-free pending + 单一 500 ms writer；4 MiB pending / 256 KiB raw event / 1 MiB ready file / 16 MiB ready directory
- PII sanitization：开启；预编译文本正则和高置信敏感字段名同时生效，含 auth/authentication/auth-header 变体
- debug logging：关闭
- aggregation：关闭
- multi-process coordination：关闭
- self-monitor/auto-throttle：开启
- dispatcher module isolation：开启；75% 高水位 / 单模块 50% 总容量上限，仅约束 NORMAL/LOW
- biz context：默认 `SYNCHRONOUS` 精确事件时刻快照，provider 必须 O(1)/无 IO/无等待锁；慢 provider 使用 `ASYNC_CACHED`，默认 1s 后台刷新并保留 LKG
- FPS report interval：1000ms 单调时间窗口；以相邻回调实际 interval / elapsed time 计算并按 refresh rate 封顶，上报 `windowDurationMs`；旧 `windowSize` 仅兼容保留
- 时间与快照：collector/持久化/file/HTTP-date 用 epoch；duration/timeout/cooldown/dedup/rate-limit/window 用 `ApmClock` 单调时钟；直接 `ApmEvent` 在异步 hand-off 前冻结三个 map
- self-diagnostics：开启；200 条 / 4 MiB 内存、256 条 / 4 MiB 队列、每进程 3 × 512 KiB 文件；不自动上传
- native crash：关闭
- Hprof/fork dump：关闭

`Apm.revokeCollectionConsent(application)` 是冷启动/停止态也能定位历史数据的撤回入口；它设置进程内 sticky gate，活动态不 drain dispatcher、不 flush aggregation，先停止 delivery，再清理 SQLite、File 与 IPC event artifacts。无参版本仅适合活动 runtime；未初始化且无法定位 app-private 目录时会诚实返回 `storageCleared=false` / `ipcFilesCleared=false`。多进程宿主必须把撤回传播到每个 SDK 进程，先关闭各自内存生产者。

AutoThrottle 退化立即生效；只有连续 3 个周期满足 drop rate <= 20% 且平均上传延迟 <= 3 秒才恢复。迟滞区间或再次退化会清零连续健康计数。SQLite 的 64 MiB 是活跃 payload 逻辑预算，不包含 page/WAL 物理开销；活动 upload lease 不为容量回收让路，因此可在租约释放或过期前临时超预算。

生产接入必须明确 endpoint/短期鉴权、固定 Ed25519 公钥、匿名稳定 installationId、隐私规则、采样限流、进程策略和服务端幂等。`apm-remote-config` 在 2 MiB/32 层/65,536 nodes/text 预算内先拒绝重复 JSON key，严格验证 key/signature Base64 与 32/64-byte 解码长度；验签成功并同步写入 app-private LKG 后才发布。过期/验签失败/rollback/equivocation 都回退可信旧值或本地默认值。

## 验证

用 JDK 21 执行 `python tools/verify_ci.py`。完整门禁覆盖 ABI、严格依赖校验、Android、model 和 Gradle 插件测试、发布候选、独立 Maven consumer 及文档。真机状态和复现实验命令见 [benchmark README](../apm-benchmark/README.md)。

## 新电脑接手

1. 克隆仓库并切到 `develop`。
2. 安装 JDK 21，并设置 `JAVA_HOME` 以运行本地门禁。Gradle daemon 与编译和测试工具链使用 JDK 21。
3. 安装 Android SDK Platform 36 与 Build Tools 36.0.0（AGP 9.5 的下限），以及项目需要的 NDK/CMake；`compileSdk` 为 36，`targetSdk` 仍为 34。
4. 按 AGENTS 读序阅读。
5. 完整客户端 CI 直接执行 `python tools/verify_ci.py`；需要逐项诊断时执行：

```powershell
git status --short --branch
git log --oneline -n 10
./gradlew.bat testDebugUnitTest
./gradlew.bat :apm-model:test
./gradlew.bat apiCheck
./gradlew.bat -p apm-plugin apiCheck
python tools/verify_api_baselines.py
python tools/verify_supply_chain_metadata.py
python tools/verify_release_candidate.py --allow-dirty
./gradlew.bat assembleDebug
./gradlew.bat -p apm-plugin test
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
./gradlew.bat :apm-benchmark:verifyReleasePerformanceBudgets
python -m unittest discover -s apm-benchmark/tests -p "test_*.py"
python apm-benchmark/verify_device_matrix.py --matrix apm-benchmark/device-lab-matrix.json --budgets apm-benchmark/device-soak-budgets.json
```

6. 发布相关变更再执行：

```powershell
python tools/verify_release_candidate.py
```

## 后续优先级

客户端代码可独立完成的既定功能缺口已经收口，包括 strict production profile/显式 consent/撤回清理、V2 batch-declared compatibility、V3 occurrence-bound wire、durable codec V4 与 V1/V2/V3 读取、动态短期 Token、签名配置、LKG、全局/模块 kill switch、动态采样/限流、HTTPS endpoint 轮换、优先级感知入口背压、单模块高水位容量隔离、dispatcher 固定阶段尾延迟证据、默认隐私保护、固定 time/allocation microbenchmark，以及 fail-closed 的 A/B/离线/重启 smoke、24h、72h 物理设备 gate。受控设备实验室准入已固化为 3 个不重叠 API lane、6 个 lane/profile 工件、OEM/设备/reset 多样性和 schema-v3 exact provenance 汇总门禁；plan-only 校验不会冒充真机执行。参考 Collector 已完成本地 V2/V3、认证、HMAC 与幂等联调；后续仍需平台凭据、生产 PostgreSQL/TLS/SigNoz、CI 管理员、符号服务或真实设备，按当前 [Collector Wire Protocol V3](protocol/COLLECTOR_WIRE_V3.md)、兼容 [V2](protocol/COLLECTOR_WIRE_V2.md) 和独立 `AndroidAPM-Server` 仓库中 `docs/云端待建设清单.md` 的协议及验收条件推进。生产 Collector 必须保留 exact whole-batch ACK、eventId 去重与冲突检测，不能把本地 codec tag、V2 batch resource 或 legacy wire 误解释成 occurrence；dispatcher 多 worker/分区吞吐若后续推进，必须先用新增阶段字段取得真实负载分布，再证明 aggregator、rate limiter、sanitizer 与 SQLite 顺序语义和线程安全。物理 smoke 的 CPU 根因已修复并在原 `20%` 上限下连续两次通过；24h/72h 与长稳功耗保留为预生产准入门槛，延期到受控设备实验室执行，不阻塞当前客户端迭代，也不能用矩阵 plan、短 smoke、host tests 或模拟器 parser 证据冒充已完成。

## Git 与文档策略

- `docs/` 纳入 Git 并随代码同步。
- `.workbuddy/`、`.github/`、`.claude/` 保持忽略。
- 不在交接文档中写“当前最新文档提交”；使用 `git log`。
- 代码/架构/构建/测试变化后同步 AGENTS、项目文档和对应模块文档。
- 对外能力或接入方式变化后同步 README。
