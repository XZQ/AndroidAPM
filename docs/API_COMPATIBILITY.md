# AndroidAPM 公共 API 兼容策略

> 同步日期：2026-07-31｜当前 SDK 版本：0.1.0

## 目标与范围

所有发布型 Kotlin 制品都提交了公开 JVM ABI 基线，包括 22 个运行时实现模块、空实现的 `apm-bundle` 分发制品和 included build `apm-plugin`。`apm-sample-app` 与非发布的 `apm-benchmark` 不属于兼容承诺。`build-logic` 是仓库构建约定，也不作为 SDK 制品发布。

项目使用 Kotlin 官方维护的 [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) 0.18.1。它会从 Android Library 的 Release 编译产物和 JVM JAR 生成 `模块/api/模块.api`，并在 `apiCheck` 中比较当前 ABI 与已提交基线。项目当前 Kotlin 2.2.21 自带的实验性 ABI 工具只在纯 JVM 模块生成了有效基线，Android Library 输出为空，因此当前不能替代该门禁；升级 Kotlin 后应重新验证 Android 输入，再依据 [Kotlin 内置 ABI 校验文档](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html)评估迁移。

基线完整性脚本还会 fail closed 地确认：

- 24 个发布制品都有且只有一个预期基线；
- 除不承载实现类的 `apm-bundle` 外，其余 23 个基线非空且包含公开声明；
- sample 与 benchmark 没有被误纳入稳定 API 范围。

## 版本规则

项目采用 SemVer 坐标，并对 `0.x` 阶段施加比 SemVer 最低要求更严格的约束：

| 版本变化 | 公共 API 规则 |
|---|---|
| `0.1.x` patch | 不允许删除、改名或改变已有公开 ABI；只允许修复和向后兼容的新增 |
| `0.x.0` minor | 默认仍保持兼容；确需破坏时必须有明确迁移说明、变更理由和人工审查，并同步更新基线 |
| `1.x.y` 及以后 | breaking change 只能进入 major；minor/patch 必须二进制兼容 |

公开 API 的计划移除应先标记 deprecated，并至少保留一个 minor 版本周期。只有安全漏洞、错误契约会造成更大风险或根本无法兼容时，才允许走明确记录的例外。

事件 wire schema、SQLite/durable codec 与诊断文件格式各自拥有独立版本和兼容规则，不能用 Maven 坐标升级代替协议迁移。Collector V2 继续遵守 append-only 字段、显式 schema version 和精确 ACK 契约。

## 变更流程

日常验证不修改基线：

```powershell
./gradlew.bat apiCheck --no-daemon
./gradlew.bat -p apm-plugin apiCheck --no-daemon
python tools/verify_api_baselines.py
```

只有确认是向后兼容的新增，或已经按上面的版本规则批准 breaking change 后，才能更新并审查基线：

```powershell
./gradlew.bat apiDump --no-daemon
./gradlew.bat -p apm-plugin apiDump --no-daemon
git diff -- */api/*.api
python tools/verify_ci.py
```

不得在 CI 中自动执行 `apiDump`，也不得为了让 `apiCheck` 变绿而无审查覆盖基线。ABI diff 必须与实现、迁移说明和版本变化放在同一提交中。

## 门禁边界

ABI 门禁能捕获类、方法、字段及 JVM 签名的删除或不兼容变化，但不能单独证明：

- Kotlin/Java 源码兼容、语义行为兼容或线程安全；
- Android Manifest、resource、consumer ProGuard/R8 规则兼容；
- 外部 Maven 元数据、依赖解析或真实宿主运行兼容；
- wire、持久化数据和服务端 Collector 兼容。

因此完整客户端门禁仍同时运行单元测试、独立 Gradle plugin 测试、基线完整性和文档校验；发布变更还必须运行 [发布与供应链门禁](RELEASE_PROCESS.md)，由独立候选仓库同时验证 Maven 元数据、Bundle/插件解析、依赖 checksum、制品哈希和 SBOM。
