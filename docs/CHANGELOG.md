# 变更历史

> 精简索引：只保留每个时间点的结论与影响，逐项证据、命令输出和真机原始数据在链接文档中。README 只描述当前事实；实际状态以源码与 `python tools/verify_ci.py` 为准。

## 未发布

- **文档**：README 按当前事实 / 使用指南 / 证据链接三层重构，日期化的变更与验证段落从 README 移出到本文件；模块表直接链到架构文档；配置默认值表收敛为决策相关项，完整 38 项留在项目文档；消费方下限提升到基线表；修正原 README 中落在 License 之后的散落条目。

## 2026-11-08

- **构建链迁移**：AGP 8.13.2 → 9.5.1、Gradle 8.13 → 9.6.0、compileSdk 35 → 36（4 处），targetSdk 保持 34。Kotlin 刻意留在 2.2.21：AGP 9 内置 Kotlin 用构建类路径上的 KGP，升到 2.4.21 会让 AAR 的 `kotlin_module` 元数据变为 2.4.0，默认 AGP 9 消费方编译直接失败——元数据版本就是消费方编译器下限。代码侧把 `DiagnosticRecorder` 的裸 `Object` 监视器换成 `ReentrantLock` + `Condition`。
- **消费方下限提升**：AAR 元数据 `minCompileSdk=36`，`com.apm:apm-plugin` 依赖 AGP 9.5.1，接入方须 AGP 9.x。KGP 2.2.x 官方矩阵上限为 Gradle 8.14，与 AGP 9.5 的 ≥9.6 无法同时满足，该偏离由完整门禁覆盖。
- **验证**：24 份 ABI 基线零变化；129 suites / 1005 tests 全 0 failures/errors/skips；全模块 `lintDebug` 0 error；sample Debug（含 ASM 插桩）与 benchmark Release AndroidTest 编译通过；25 坐标候选与独立 consumer 在严格依赖校验下 clean assembleDebug；四组指纹重建为 473 / 218 / 206 / 281。
- **同日其他**：compileSdk 35 迁移（ABI 零变化）；补 4 个零覆盖测试文件（全项目 test/main 比 0.62 → 0.64）；删除 `docs/superpowers/` 下 10 个零引用过程文档；全量文档同步；发布候选脏树身份改为 `git diff --binary --no-textconv`，避免 `.docx` 变更触发 Git textconv 对本机 `file` 命令的依赖。

详见 [项目文档](Android_APM_项目文档.md) 的 2026-11-08 条目与 [AGENTS.md](../AGENTS.md)。

## 2026-10-13

- 深度审查修复 16 项：init 所有权、旧 ANR/Hprof/Launch 回调的会话隔离、WebView 宿主回调与后台线程约束、上传边界、签名配置 scope、完整 Header 脱敏、ART Hprof 流式布局、Launch 时钟等；既有 wire/codec/SQLite schema 保持不变。详见 [审查记录](REVIEW_2026_10_13.md)。
- 最终本地验收：119 suites / 886 tests、25 坐标候选、`sourceDirty=false`、独立 consumer 完成 ASM transformation；当轮未执行真机与 Collector E2E，也未向外发布。

## 2026-09-23

- 慢方法插件复核：`transformJar`/`transformDirectory` 改为保留输入 class 的精确 StackMapFrame、仅重算最大栈，避免类加载期 `VerifyError`；Windows 反斜杠 class 路径在匹配前归一化。该结果是 JVM/构建验证，不替代 ART/OEM 真机。

## 2026-09-12

- **聚合隐私**：聚合输入先按原字段名与类型脱敏，周期与关闭 flush 不再重复执行自定义规则；数字文本（含前导零、状态码）保持文本维度，只对显式 Number 计算统计。
- **OkHttp 结算**：默认 EventListener 在 callEnd/callFailed 结算唯一 summary，`totalMs` 含 body 消费/关闭；与拦截器同时接入时按 Call/模块协商所有权，避免重复。
- **历史退出归属**：`app_exit` 使用系统记录的历史进程与退出前保存的版本/构建/安装身份，无法恢复时明确丢弃并记录 `HISTORICAL_OCCURRENCE_UNAVAILABLE`，不冒用当前 release。
- **完整门禁**：Android/model/plugin 共 817 项测试零失败，ABI、严格依赖校验、Maven 候选与独立消费、示例/benchmark 构建、真实 V2/V3 Collector 联调均通过（源码 `62665ae`）。

## 2026-09-07

- 可靠性：HTTP 支持活动请求取消，撤回结果通过 `uploadWorkerStopped` 报告退出证据；上传退避不再被新事件通知打断。
- 字段口径：事件字段接受不可变标量，可变文本在入口有界复制并脱敏，其他对象以 `[unsupported]` 替换；FrameMetrics 的空闲绘制间隔不再计为卡顿，OkHttp 成功的连接重试按最终成功统计。

## 2026-08-02

- Android Studio Sync 会下载的 source classifier 纳入严格 SHA-256 校验：root / `apm-plugin` / `build-logic` 分别登记 298 / 154 / 176 个源码制品，并补齐缓存内 POM/module 与缺失的 BOM/Groovy component；未关闭 verification，也未扩大 trusted group。

## 2026-07-31

- **验证基线**：`--rerun-tasks` 强制重跑根 Android 98 suites / 663 tests、model 5 / 51、plugin 1 / 18，全零失败；根 Android + model 完整基线 103 suites / 714 tests。`apiCheck` 覆盖 23 个根发布制品与 included plugin，24 份基线中仅 `apm-bundle` 为空（与其无实现类分发设计一致）。
- **隐私、安全与协议加固**：固定种子语料覆盖 durable 截断/位翻转/非法 UTF-8、远程配置 JSON 变异与重复 key、PII 分隔符与大小写、V2 ACK 非规范整数、`Retry-After` 乘法溢出；确定性回归不替代持续 fuzz 或第三方渗透测试。
- **Collector 联调**：与 `codex/collector-v2-e2e`（tip `2feb2f5`）完成真实 HTTP 闭环，两次发送同一 2-event V2 Gzip batch 后 SQLite 仍为 2 个唯一 `eventId`；PostgreSQL/Compose/SigNoz 仍是外部待验收项。
- **性能预算**：除 codec 与 SQLite 外新增 32 次 `Apm.emit` 准入与 2,048 满队列 HIGH 插入/LOW 淘汰两项契约；新两项只完成 AndroidTest 编译，历史三项真机结果不能外推。
- **设备实验室准入**：三个不重叠的物理 ARM64 lane（24–28 smoke、29–33 smoke/24h、34–36 smoke/24h/72h）与覆盖要求写入矩阵；不带 `--results` 时只校验计划与预算 schema。

## 2026-07-23 ~ 07-25

- **真机 smoke 与 OEM 差异**：Redmi `22041216UC` 的 FPS 采集改为 API 24+ event-driven FrameMetrics（Choreographer 仅作失败回退）后，主线程 CPU 从 28–32% 降到 11–13%，同一 APK 两轮通过 20% 门禁；OnePlus `PLK110` 拒绝 ADB shell `pm clear`，改用限定包卸载重装回退并通过 schema-v2 smoke。
- **功耗采集**：OEM 隐藏 sample UID 时回退到 current checkin 的 `pwi,uid`，并要求累计值严格增长，避免把 `0→0` 或计数回退伪装成 `0 mAh/hour`。
- **24h/72h**：首次后台 24h 因设备持续离线正确失败且未生成 JSON；长 profile 只读重连窗口改为 300 秒、绝对上限 600 秒。随后项目决定不再长期占用个人手机，`24h`/`72h` 作为预生产门槛延期到受控设备实验室执行。

真机原始数据与采集契约见 [benchmark 文档](../apm-benchmark/README.md) 与 [benchmark 架构文档](architecture/22_apm-benchmark.md)。
