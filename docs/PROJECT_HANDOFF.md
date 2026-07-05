# AndroidAPM 项目交接快照

> 快照日期：2026-07-06  
> 最近全量验证：2026-07-04  
> 当前分支：`develop`  
> 交接整理前已推送 HEAD：`a7b2a0a Docs: Sync runtime hardening commit`

本文档用于换电脑、换模型或长期暂停后快速接手。仓库内事实源优先级为：

1. `AGENTS.md`
2. `docs/Android_APM_项目文档.md`
3. `docs/PROJECT_HANDOFF.md`
4. `docs/architecture/00_整体架构.md`
5. 对应模块的 `docs/architecture/*.md`

## 当前结论

当前仓库主体功能、工程硬化、本地验证、文档同步和 GitHub 推送都已完成。`develop` 已推送到 GitHub，最近验证过的实现提交是：

```text
cd2a409 Refactor: Harden runtime delivery and native alignment
```

随后追加的文档记录基线提交是：

```text
a7b2a0a Docs: Sync runtime hardening commit
```

本地工作区允许存在未跟踪的 `.claude/` 目录；它是本机私有状态，不属于项目交付范围，也不应作为代码/远端不一致判断依据。

## 已完成范围

| 范围 | 状态 | 说明 |
|------|------|------|
| 15 个监控模块 | 已完成 | memory、crash、anr、launch、network、fps、slow-method、io、battery、sqlite、webview、ipc、thread-monitor、gc-monitor、render |
| 4 个基础模块 | 已完成 | apm-model、apm-core、apm-storage、apm-uploader |
| 2 个扩展模块 | 已完成 | apm-trace、apm-otel-exporter |
| Gradle 插件 | 已完成 | `apm-plugin` 使用 AGP instrumentation API，不依赖 legacy Transform |
| 默认数据链路 | 已完成 | SQLite durable outbox + `PersistentUploadWorker` + 成功确认删除 |
| 多进程事件交接 | 已完成 | `.tmp` 写入、`.ipc` ready 文件发布，critical 事件可同步 IPC handoff |
| HTTP 上传 | 已完成 | 单请求批量、默认 Gzip、`ApmConfig.enableHttpGzip` 可关闭 |
| Native 兼容 | 已完成 | IO/Crash/Memory JNI 目标已设置 16KB page-size linker alignment |
| 发布验证 | 已完成 | sources JAR/AAR Maven 元数据、本地 Maven 发布、独立 smoke consumer 构建 |
| 文档基线 | 已完成 | 项目文档、README、架构文档已同步到 2026-07-04 验证基线 |

## 最新验证链

全量验证基线在 2026-07-04 通过，环境为 JDK 21、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21。

```bash
./gradlew testDebugUnitTest
./gradlew -p apm-plugin test
./gradlew assembleDebug lintDebug
./gradlew assembleRelease
./gradlew publishToMavenLocal
./gradlew -p smoke-tests/maven-consumer clean assembleDebug
```

本次 2026-07-06 文档交接整理不修改代码，因此不刷新“全量验证日期”。如后续改代码、构建脚本、模块架构或发布配置，必须重新跑相关验证并同步 `AGENTS.md` 与 `docs/Android_APM_项目文档.md`。

## 真实未完成项

这些不是当前交付阻塞，而是后续产品化、规模化或线上化增强。

| 优先级 | 事项 | 当前状态 | 下一步 |
|--------|------|----------|--------|
| P1 | 生产端接收与后台 | SDK 已提供 HTTP uploader、自定义 uploader、OTel 语义映射；仓库内没有生产 APM 后台服务 | 接入真实 collector、鉴权、租户、指标查询和告警后台 |
| P1 | 并发 durable upload lease | 当前 durable worker 是单 worker 模型，测试覆盖成功/失败/fallback；未设计多 worker 抢占锁 | 引入多 worker 或跨进程上传前，先增加 batch claim/lease/expire 机制 |
| P2 | 真机长稳与性能压测 | 已通过本地 Gradle/JVM/lint/release/publish/smoke；未记录长时间真机 soak 测试 | 在样机上跑前后台、崩溃、ANR、网络、IO、FPS 长稳脚本并沉淀报告 |
| P2 | 渲染过度绘制检测 | `apm-render` 已做 View 数量/层级检测，过度绘制仍列入 Roadmap | 设计 GPU overdraw 或 FrameMetrics 相关采样方案 |
| P2 | Native 符号化与 tombstone 管线 | Native crash 安全重抛和 tombstone 扫描已完成；线上符号化服务不在仓库内 | 建立符号表上传、tombstone 解析、后台聚合链路 |
| P2 | 外部 Maven 发布 | `publishToMavenLocal` 和 smoke consumer 已验证；未发布到 Maven Central/私有制品库 | 补签名、坐标、仓库凭据和发布流水线 |
| P3 | 文档二进制产物刷新 | Markdown 是事实源；`docs/*.docx`、`docs/记录.zip`、`docs/绘制.jpeg` 是历史/展示产物 | 需要对外交付时再从 Markdown 生成或人工刷新二进制文档 |
| P3 | CI 云端验证 | 本地完整验证已通过；是否有云端 CI 不是当前基线的一部分 | 若启用 GitHub Actions，复用本地验证链并缓存 Gradle/Android SDK |

## 新电脑接手步骤

1. 克隆仓库并切到 `develop`。
2. 确认 JDK 21 可用，Android SDK 包含 compileSdk 34、NDK/CMake 可构建 JNI。
3. 按 `AGENTS.md` 的读序阅读文档。
4. 执行：

```bash
git status --short --branch
git log --oneline -n 10
./gradlew testDebugUnitTest
./gradlew -p apm-plugin test
./gradlew assembleDebug lintDebug
```

5. 如果要验证发布和消费，再执行：

```bash
./gradlew assembleRelease
./gradlew publishToMavenLocal
./gradlew -p smoke-tests/maven-consumer clean assembleDebug
```

## 维护规则

- 本文档不自引用“当前最新提交号”；换机后以 `git log --oneline -n 5` 查看实际 HEAD。
- 代码、架构、构建、测试或发布状态变化后，同步 `AGENTS.md` 与 `docs/Android_APM_项目文档.md`。
- 用户可见能力变化后，同步 `README.md`。
- 模块设计变化后，同步对应 `docs/architecture/*.md`。
- 长期规则写入 `CLAUDE.md`；临时进度和验证结果写入项目文档或本交接文档。
- 不要把 `.claude/`、本机 IDE 配置、临时测试数据库、构建产物写入项目状态结论。
