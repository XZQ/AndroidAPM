# CLAUDE.md — Android APM Project

## 接手与读取
接手时按序读取：`AGENTS.md` → `docs/Android_APM_项目文档.md` → `README.md` → `docs/architecture/00_整体架构.md` → 目标模块文档。
状态以仓库内文档为准，不以模型记忆为准。修改前先读对应 `docs/architecture/` 文档。

## 编码规范（强制，所有文件）
1. **KDoc**：所有属性/方法（含 private、接口方法）必须有 `/** */` 注释
2. **行内注释**：分支/循环/异常/赋值/回调处说明意图
3. **命名常量**：魔法数字/字符串提取为 `const val`（0/1/-1 除外）；禁止裸包名反射引入类

## 项目信息
- Multi-module Gradle (Kotlin, AGP 7.4.2, compileSdk 34, minSdk 24)
- 构建命令见 MEMORY.md 或 AGENTS.md

## Git 提交
英文，格式 `Type: Subject`。Type：Feat / Fix / Refactor / Perf / Style / Docs / Revert / Build。

## 变更后同步（强制）
代码/架构/构建变更后，同步更新：
1. `~/.claude/projects/-home-didi-AI-APM/memory/MEMORY.md`（日期、hash、状态）
2. `AGENTS.md` + `docs/Android_APM_项目文档.md`（事实源）
3. 如有必要：`README.md`、`docs/architecture/*.md`

CLAUDE.md 只放长期规则，不放临时进度。
