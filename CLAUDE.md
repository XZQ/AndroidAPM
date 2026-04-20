# CLAUDE.md — Android APM Project

## 接手顺序（强制）

任何模型或开发者接手本项目时，必须按以下顺序读取：

1. `AGENTS.md`
2. `docs/Android_APM_项目文档.md`
3. `README.md`
4. `docs/architecture/00_整体架构.md`
5. 本次要修改的具体模块文档

说明：
- `AGENTS.md` 是仓库内交接入口。
- 当前项目状态、构建测试结果、最近接线变化，以仓库内文档为准，不以模型记忆为准。

## 编码规范（强制）

以下三条规则对所有模块、所有文件生效，包括未来新增的代码：

### 规则一：成员变量和方法必须添加 KDoc 注释
- 所有 `public`、`internal`、`private` 的属性和方法都需要有注释
- 格式：`/** 简要描述 */` 或多行 KDoc
- 接口方法也必须注释

### 规则二：方法内关键节点必须添加行内注释
- 分支逻辑（when/if-else）说明判断意图
- 循环/遍历说明目的
- 异常处理说明原因
- 赋值/转换说明业务含义
- 回调/lambda 说明触发时机

### 规则三：所有常量必须提取为命名常量
- 魔法数字 → `private const val` 或 `companion object` 常量
- 魔法字符串 → 命名常量
- 配置默认值 → 统一在 Config 类中定义
- 不允许在代码中出现裸数字/裸字符串（除 0、1、-1 等基本值）

## 项目信息
- Multi-module Gradle (Kotlin, AGP 7.4.2, compileSdk 34)
- Build: `JAVA_HOME=/home/didi/.jdks/jbr_dcevm-11.0.16 ./gradlew assembleDebug`
- 最新模块构成、构建状态、测试状态以 `AGENTS.md` 和 `docs/Android_APM_项目文档.md` 为准
- 修改前先读取对应 `docs/architecture/` 文档，确认设计口径

## Git提交（强制）
- 所有提交消息必须使用**英文**
- 前缀首字母大写，格式：`Type: Subject`
- 功能业务提交用 Feat:
- Bug提交用 Fix:
- 重构代码(不改变功能)提交用 Refactor:
- 性能优化提交用  Perf:
- 代码风格调整提交用  Style:
- 文档更新提交用 Docs:
- 回滚提交用 Revert:
- 构建/依赖/脚本调整/CICD  Build:

## Commit 后更新 MEMORY.md（强制）

每次 `git commit` 成功后，**必须**同步更新 `~/.claude/projects/-home-didi-AI-APM/memory/MEMORY.md`：

1. 更新 `最近更新` 区块：日期、最近 commit hash、构建/测试状态
2. 如有模块变更，更新 `模块状态总表`
3. 如有架构决策变更，更新 `架构关键决策`
4. 如有新技术要点，补充到 `技术要点备忘`

## 状态同步（强制）

任何模型完成有意义的代码、架构、构建、测试、文档变更后，必须同步更新仓库内状态文件：

1. `AGENTS.md`
2. `docs/Android_APM_项目文档.md`
3. 如用户可见能力变化，补充更新 `README.md`
4. 如模块设计变化，补充更新对应 `docs/architecture/*.md`

同步内容至少包括：
- 最新验证日期
- 构建/测试结果
- 模块或接线状态变化
- 提交 hash（提交后）

约束：
- `AGENTS.md` 是仓库内交接入口。
- `docs/Android_APM_项目文档.md` 是仓库内当前状态事实源。
- `CLAUDE.md` 只放长期规则，不记录易过期的临时进度。
- 外部 `MEMORY.md` 仅作为 Claude 工作流镜像，不替代仓库内状态文件。
