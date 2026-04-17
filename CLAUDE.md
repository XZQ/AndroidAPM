# CLAUDE.md — Android APM Project

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
- 模块: apm-core, apm-model, apm-storage, apm-uploader, apm-memory, apm-crash, apm-anr, apm-launch, apm-network, apm-sample-app
- 读取docs目录的文档，了解当前项目的进度

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
