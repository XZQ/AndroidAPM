# AndroidAPM 发布与供应链门禁

> 同步日期：2026-07-31｜当前 SDK 版本：0.1.0

## 发布范围

一次完整分发包含 25 个 Maven 坐标：

- 23 个根发布制品：22 个运行时模块和 `apm-bundle`；
- `com.apm:apm-plugin:0.1.0`；
- `com.apm.slow-method:com.apm.slow-method.gradle.plugin:0.1.0` 插件 marker。

`apm-sample-app`、`apm-benchmark` 和 `build-logic` 不得进入发布仓库。Bundle POM 必须精确传递 22 个运行时制品；插件 marker 必须只依赖同版本 `com.apm:apm-plugin`。

## 本地发布候选

在 clean worktree 和 JDK 17+ 环境执行：

```powershell
python tools/verify_release_candidate.py
```

开发中的未提交修改可临时增加 `--allow-dirty`，但这种结果会在 manifest 中记录 `sourceDirty=true`，不能作为发布来源证明。门禁会：

1. 清理且只清理 `build/release-candidate/repository`；
2. 将 23 个根制品及 Gradle 插件/marker 发布到独立文件仓库；
3. fail closed 校验 25 个坐标、POM 必填元数据、固定依赖版本、Bundle/marker 依赖图、AAR/JAR/sources/Gradle metadata、ZIP 路径安全、敏感文件名和 Java 17 class major；
4. 生成逐文件 SHA-256 清单 `build/reports/release-candidate/release-manifest.json`；
5. 生成包含分发坐标和 POM 直接依赖的 SPDX 2.3 SBOM `build/reports/release-candidate/release-sbom.spdx.json`；
6. 让隔离 consumer 仅从该仓库解析 `apm-bundle` 与 `com.apm.slow-method`，再清理构建。

清单和 SBOM 是生成型验证工件，不提交到 Git。clean candidate 的 `sourceIdentity` 等于 commit；dirty 开发候选则额外哈希 tracked diff 与 untracked source，避免多个不同工作树复用同一 SPDX namespace。SBOM 描述已发布坐标及其 POM 直接依赖，不冒充完整运行环境、容器镜像或 SLSA provenance。

## 依赖完整性

根构建、`apm-plugin`、`build-logic` 和隔离 consumer 各自提交 `gradle/verification-metadata.xml`，对解析到的外部构建依赖执行严格 SHA-256 校验：

```powershell
python tools/verify_supply_chain_metadata.py
```

consumer 只信任本次生成的 `com.apm` 与插件 marker；这些文件已由候选门禁逐文件重算哈希。任何更宽的 trusted group、正则信任、缺失 checksum 或缺失 build-root 元数据都会失败。升级依赖时必须使用 Gradle 的 `--write-verification-metadata sha256` 重新收集实际工件，人工审查坐标和 checksum diff，再运行完整门禁；不得为了绕过失败而扩大 trust。

Android Studio Sync 的 tooling model 会额外解析 Gradle、AGP、Kotlin 插件及其传递依赖的 source classifier。它们不是运行时依赖，但仍属于被下载的供应链输入，必须在对应 build root 中提交精确 checksum。Sync 报告出现 `detachedConfiguration1` 或 `compileClasspath` verification failure 时，先确认是 missing checksum 还是 mismatch，再与 Google Maven、Maven Central 或 Gradle 官方分发源交叉核验；不得以关闭 verification、信任所有 `*-sources.jar` 或增加宽泛正则来修复 IDE 导入。

## 签名候选

真正准备上传前，使用 Gradle keyring 属性，或仅通过环境变量注入 ASCII-armored 私钥：

```powershell
$env:SIGNING_KEY = Get-Content .\private-signing-key.asc -Raw
$env:SIGNING_PASSWORD = "<secret>"
python tools/verify_release_candidate.py --require-signatures
```

`--require-signatures` 会让缺少签名配置的构建在配置期失败，并要求每个 POM、Gradle metadata、AAR/JAR 和 sources artifact 都具有结构完整的 detached armored PGP signature。私钥、口令和仓库凭据不得写入仓库、Gradle 命令输出、manifest 或 SBOM。

## 外部 Maven 仓库

通用 HTTPS Maven 仓库通过环境变量显式启用；URL 和用户名也可使用 `apmExternalRepositoryUrl` / `apmExternalRepositoryUsername` Gradle property，口令只接受环境变量，避免出现在命令行参数中：

```powershell
$env:APM_RELEASE_REPOSITORY_URL = "https://packages.example.com/releases"
$env:APM_RELEASE_REPOSITORY_USERNAME = "<user>"
$env:APM_RELEASE_REPOSITORY_PASSWORD = "<secret>"
$env:SIGNING_KEY = Get-Content .\private-signing-key.asc -Raw
$env:SIGNING_PASSWORD = "<secret>"

./gradlew.bat publishAllPublicationsToExternalReleaseRepository --no-daemon
./gradlew.bat -p apm-plugin publishAllPublicationsToExternalReleaseRepository --no-daemon
```

外部 URL 非 HTTPS、用户名/密码缺失或 PGP 签名缺失时，构建在上传前失败。仓库自身没有任何真实外部凭据，也没有 Maven Central/私有仓库 staging、close、promotion 或下载复核证据；这些仍需目标仓库账号和仓库特定流程完成。对外宣布可用前还必须从目标仓库重新解析同版本 Bundle 与插件，并保存仓库侧 staging/promotion 结果。

## 发布顺序

1. 按 [API 兼容策略](API_COMPATIBILITY.md) 确认版本和 ABI；
2. 在 clean commit 上运行 `python tools/verify_ci.py`；
3. 运行签名候选门禁并保存 manifest/SBOM；
4. 上传到目标仓库的 staging 区；
5. 从 staging 重新消费 Bundle 和插件；
6. 执行仓库特定 close/promotion；
7. 推送版本 tag，并记录 tag、commit、候选 manifest、SBOM 和仓库 promotion 身份。

任一步失败都不得继续 promotion，也不得用 `publishToMavenLocal` 或未签名候选代替外部发布证据。
