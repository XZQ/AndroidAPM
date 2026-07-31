# CLAUDE.md — Android APM Project

## 接手与读取
接手时按序读取：`AGENTS.md` → `docs/Android_APM_项目文档.md` → `docs/PROJECT_HANDOFF.md` → `README.md` → `docs/architecture/00_整体架构.md` → 目标模块文档。
状态以当前源码和可执行验证为准；仓库文档负责记录该事实，不得反过来覆盖代码事实。修改前先读对应 `docs/architecture/` 文档。

## 编码规范（强制，所有文件）
1. **KDoc**：所有属性/方法（含 private、接口方法）必须有 `/** */` 注释
2. **行内注释**：分支/循环/异常/赋值/回调处说明意图
3. **命名常量**：魔法数字/字符串提取为 `const val`（0/1/-1 除外）；禁止裸包名反射引入类

## 项目信息
- Multi-module Gradle (Kotlin 2.2.21, AGP 8.13.2, Gradle 8.13, Java 17 toolchain, Gradle runtime JDK 17+, compileSdk 34, minSdk 24)
- JVM 字节码目标保持 Java 17；构建与验证命令见 `AGENTS.md`
- `settings.gradle.kts` 对 Gradle runtime JDK 17+ fail fast；完整客户端 CI 使用 `python tools/verify_ci.py`
- 发布型 Kotlin 制品的公开 ABI 必须通过 `apiCheck`；版本与基线更新遵守 `docs/API_COMPATIBILITY.md`

## Git 提交
英文，格式 `Type: Subject`。Type：Feat / Fix / Refactor / Perf / Style / Docs / Revert / Build。

## 变更后同步（强制）
代码/架构/构建变更后，同步更新：
1. `AGENTS.md` + `docs/Android_APM_项目文档.md`（仓库内事实源）
2. 如有必要：`README.md`、`docs/architecture/*.md`

`docs/` 属于项目交付并纳入 Git；`.workbuddy/`、`.github/`、`.claude/` 为本地状态，不纳入 Git。

CLAUDE.md 只放长期规则，不放临时进度。
