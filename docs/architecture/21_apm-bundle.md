# apm-bundle 单依赖分发

> 同步日期：2026-07-22｜以 `apm-bundle/build.gradle.kts` 和发布 POM 为准

## 1. 目标

`apm-bundle` 为需要完整 AndroidAPM 客户端能力的宿主提供一个依赖坐标：

```kotlin
dependencies {
    implementation("com.apm:apm-bundle:0.1.0")
}
```

源码复合工程可对应使用 `implementation(project(":apm-bundle"))`。该模块不承载 SDK 实现类，价值来自 Maven POM 的传递依赖；宿主最终使用的仍是各细粒度模块的 AAR/JAR。

## 2. 暴露范围

Bundle 通过 `api(project(...))` 暴露 22 个运行时制品：

- 基础与控制面：`apm-model`、`apm-storage`、`apm-uploader`、`apm-core`、`apm-remote-config`
- 监控模块：Memory、Crash、ANR、Launch、Network、FPS、Slow Method、IO、Thread、Battery、SQLite、WebView、IPC、GC、Render
- 扩展模块：`apm-trace`、`apm-otel-exporter`

以下构建单元不属于 Bundle：

- `apm-plugin`：Gradle included build，慢方法 ASM 仍需宿主显式应用 `com.apm.slow-method`
- `build-logic`：仅提供仓库构建约定
- `apm-sample-app`：接入示例，不是 SDK 制品
- `apm-benchmark`：非发布测量入口

## 3. 运行时边界

添加 Bundle 只解决依赖声明，不改变 SDK 生命周期：

- 不自动调用 `Apm.init`
- 不自动注册 15 个监控模块
- 不把 Network、SQLite、WebView、IPC、Battery、IO 等显式接入变成全局 Hook
- 不自动应用慢方法 Gradle 插件
- 不改变 dispatcher、SQLite outbox、uploader 或远程配置的默认行为

因此 Bundle 不是“零配置自动采集”开关。初始化方式、模块注册和显式接线仍以各模块文档为准。

## 4. 模块化取舍

完整 Bundle 会把全部运行时模块及其第三方依赖带入宿主解析图，适合功能完整性优先、希望统一版本和简化依赖声明的接入方。只使用少数监控域或严格控制包体/依赖面的宿主应继续直接选择细粒度制品。

两种方式使用相同的模块实现和运行时语义；差异仅在消费侧依赖集合，不应形成两套 SDK 行为。

## 5. 验证契约

本地发布验证必须同时证明：

1. `publishToMavenLocal` 生成 `com.apm:apm-bundle:0.1.0`；
2. Bundle POM 对 22 个 `com.apm` 运行时制品声明 compile-scope 依赖；
3. 隔离 Maven consumer 只声明 Bundle，仍能编译多个传递模块的代表性 API；
4. Bundle Release AAR 和 lint 构建通过。

这些检查只证明本地制品图完整。仓库没有 Maven Central 或外部私有仓库发布凭据，因此不能据此宣称外部制品已经可用。
