# AndroidAPM Collector Wire Protocol V3

> 状态：客户端与参考服务端协议已冻结并完成本地真实 uploader E2E｜同步日期：2026-08-28｜生产 PostgreSQL/TLS/SigNoz 与故障验收仍属于现场门禁

## 1. 适用范围

本协议对应 `SerializationFormat.PROTOBUF_ENVELOPE_V3`。V3 在 V2 typed field、固定 resource、稳定 batch ID、大小预算和 exact whole-batch ACK 之上，要求每条事件携带发生时冻结的 release、build、installation 与可选 Native frame 身份。

`LINE_PROTOCOL`、standalone `PROTOBUF` 和 `PROTOBUF_ENVELOPE_V2` 继续作为兼容协议，不能被服务端静默解释成 V3。`ApmRuntimeProfile.PRODUCTION_STRICT` 的 built-in HTTP 路径要求 V3；显式 custom uploader 自行承担等价的 occurrence、持久化与 ACK 契约。

## 2. 客户端初始化

V3 必须使用 occurrence-aware overload，并在宿主 manifest 移除自动初始化 Provider：

```kotlin
Apm.init(
    application,
    ApmConfig(
        endpoint = "https://collector.example.com/v1/events",
        runtimeProfile = ApmRuntimeProfile.PRODUCTION_STRICT,
        initialCollectionConsent = CollectionConsent.GRANTED,
        serializationFormat = SerializationFormat.PROTOBUF_ENVELOPE_V3,
        resourceContext = ApmResourceContext(
            serviceName = application.packageName,
            serviceVersion = BuildConfig.VERSION_NAME,
            deploymentEnvironment = "production",
            installationId = installationIdStore.anonymousId()
        )
    ),
    ApmOccurrenceContext(
        serviceVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toString(),
        appBuild = BuildConfig.GIT_SHA,
        variant = BuildConfig.BUILD_TYPE,
        installationId = installationIdStore.anonymousId()
    )
)
```

当前 `ApmInitProvider` 只能调用二参数 `Apm.init(application, config)`，无法提供 occurrence snapshot；若配置 V3，该路径会 fail closed。V3 宿主必须采用手动初始化并通过 manifest merger `tools:node="remove"` 移除 Provider，不能把 Provider 捕获并记录的初始化失败当作已启用采集。

初始化时 SDK 会复制 occurrence 和 Native frame list。所有经 `ApmContext` 发出的 V3 事件在异步、IPC 或 SQLite hand-off 前绑定该 host release/build/installation；监控模块可以在事件自身的 occurrence 中补充该事件的 Native frames，但不能覆盖 host 身份。

## 3. 请求

- Method：`POST`
- Content-Type：`application/x-protobuf; message=ApmBatchEnvelope; version=3`
- Content-Encoding：可选 `gzip`
- Body：`apm-model/src/main/proto/apm_event.proto` 的 `ApmBatchEnvelope`

协议 Header：

| Header | 值 |
|---|---|
| `X-Apm-Schema-Version` | `3` |
| `X-Apm-Sdk-Version` | 客户端制品版本，当前 `0.1.0` |
| `X-Apm-Batch-Id` | 与 body 相同的 `b3-` batch ID |
| `X-Apm-Event-Count` | 本物理请求中的完整事件数 |

这些 Header 由 transport 拥有，静态或动态宿主 Header 不能覆盖。

## 4. Batch envelope 与大小

V3 沿用 V2 envelope 字段：`schema_version/sdk_name/sdk_version/batch_id/sent_at_ms/resource/events`。`sent_at_ms` 是请求创建时间，不是事件发生时间，也不参与幂等身份。

batch ID 对相同有序 event set 跨重试稳定：

```text
"b3-" + hex(first16bytes(SHA-256(
  0x03 ||
  int32be(len(eventId1Utf8)) || eventId1Utf8 ||
  int32be(len(eventId2Utf8)) || eventId2Utf8 || ...
)))
```

`maxUploadBatchBytes` 默认 1 MiB、绝对上限 4 MiB，针对 gzip 前的完整 envelope。uploader 按真实编码字节拆成物理 batch；单事件超预算时不打开连接，durable outbox 保留该事件。任一较早物理 batch 已 ACK 而后续 batch 失败时，逻辑 claim 仍可能整组重发，服务端必须继续按 `(tenant_id,event_id)` 去重。

### 历史 outbox 升级策略（2026-09-07）

可解码的 durable V1/V2/V3 行可能没有 occurrence。V3 worker 组批前逐行 preflight，内置 SQLite 对缺失/非法身份的行执行 owner-aware discard，计入 `UPLOAD_PROTOCOL_REJECTED`，其余行正常发送并要求精确 ACK。该策略明确丢弃不能满足 V3 契约的历史数据，不伪造发生时版本，也不静默改发 V2。自定义 store 未实现可选 discard 接口时，只对拒绝行应用既有 retry/TTL；直接 `HttpApmUploader.uploadBatch` 仍保留 whole-batch Boolean 契约，混入不兼容行返回 false。

## 5. Resource 与 occurrence 的事实等级

V3 仍发送四个 batch resource 字段：

- `service_name`
- `service_version`
- `deployment_environment`
- `installation_id`

resource 用于认证 scope 一致性、路由和兼容诊断，属于上传批次声明。它不能覆盖 event field 16 中的 occurrence。应用升级后，新进程可能使用新 resource 上传旧 outbox 行；因此版本归因、发布比较和精确符号化必须读取 occurrence，不能读取 request Header 或 batch resource 猜测发生版本。

tenant/app/environment 只来自已认证 credential scope。客户端 resource、Header 或事件字段都不能自行改变租户边界。

## 6. Event occurrence 字段

V3 event 必须：

- 使用 field 15 `typed_fields`，不得同时写 legacy field 10 `fields`；
- 设置 field 16 `occurrence`；
- 保留 field 14 `event_id`，作为最终事件级幂等身份。

`ApmOccurrenceContext`：

| Field | Proto | 规则与用途 |
|---|---:|---|
| `service_version` | 1 | 发生时应用 version name；非空、trimmed、有界 |
| `version_code` | 2 | ASCII canonical unsigned decimal；多位数不得以 `0` 开头 |
| `app_build` | 3 | 不可变 build/release 标识；非空、trimmed、有界 |
| `variant` | 4 | build variant 或分发变体；非空、trimmed、有界 |
| `installation_id` | 5 | 宿主生成的匿名安装标识；非空、trimmed、有界 |
| `native_frames` | 6 | 0–256 个可选 build-relative frame |

客户端每个普通 identity 最多 256 个 UTF-16 字符；参考服务端进一步要求最多 256 个 UTF-8 bytes。`version_code` 服务端最多 64 bytes。生产接入应在宿主侧使用更严格的 UTF-8 byte 预算，避免只在服务端拒绝。

`ApmNativeFrameIdentity`：

| Field | Proto | 规则 |
|---|---:|---|
| `abi` | 1 | 非空、trimmed、有界，例如 `arm64-v8a` |
| `module_build_id` | 2 | 精确 linker build ID，非空、trimmed、有界 |
| `module_name` | 3 | 稳定模块名，非空、trimmed、有界 |
| `module_relative_pc` | 4 | 非负、相对模块装载地址的 PC |
| `load_bias` | 5 | 可选非负 ELF load bias |

V3 schema 缺少 occurrence、identity 不完整、地址非法、frame 超限或同时出现 field 10/15 时，服务端必须整批拒绝。frame 可以为空；为空表示该事件没有可用于精确 Native 符号化的 frame 证据，不得用当前进程地址或 batch build 猜测。

## 7. Durable codec 与公开 ABI

V3 发生时身份先进入客户端 durable codec format V4，再进入 SQLite/IPC。format V4 在 V3 typed scalar/eventId 字节之后 append occurrence snapshot，继续读取 format V1/V2/V3；旧行不会被虚构 occurrence。

为保持 0.1.x ABI：

- `ApmEvent` 原 primary constructor、`copy/copy$default/componentN` 不变；
- occurrence 通过只读 `ApmEvent.occurrence` 与 `withOccurrenceContext(...)` 增量公开；
- `Apm.init(application, config, occurrenceContext)` 是增量 overload；
- `ApmConfig` constructor/copy/component ABI 不因 V3 增参。

直接调用 `ApmEvent.copy(...)` 不会自动携带 occurrence；SDK 内部所有发生 copy 的异步、脱敏、IPC 和迁移路径都显式恢复 snapshot。外部自定义管线若复制 V3 event，必须调用 `withOccurrenceContext` 保留 occurrence。

## 8. Installation 假名化

wire 与客户端 outbox 中的 `installation_id` 必须是宿主生成的匿名值，不得使用 user ID、广告 ID、IMEI、手机号、Token 或其他直接身份。

参考服务端在认证和完整 batch 校验后，以固定 domain、authenticated tenant 和版本化 secret 计算 HMAC-SHA-256，只持久化 HMAC 与 key version，并在写 durable inbox 前删除 occurrence/resource 中的 installation 明文。相同明文在不同 tenant 下必须得到不同假名。HMAC 是受控假名化，不是匿名化，仍受访问、保留、导出和删除策略约束。

## 9. ACK 与冲突

HTTP 2xx 只有同时返回以下精确响应 Header 才表示该物理 batch 完整接受：

| Header | 验收条件 |
|---|---|
| `X-Apm-Schema-Version` | 精确等于 `3` |
| `X-Apm-Batch-Id` | 精确等于请求 batch ID |
| `X-Apm-Event-Count` | 精确等于请求事件数 |

缺失、格式错误、不匹配、非 2xx 或 partial accept 都必须返回失败，客户端保留 durable rows。成功 ACK 只能在完整 batch 数据库事务提交后产生。

同一 tenant/eventId 的完全相同重放成功 ACK；payload、occurrence、release/build/native identity 或认证 scope 内容发生变化时必须返回 `409 event_id_conflict`，不能把冲突当作普通 duplicate。

## 10. 兼容与当前证据

- V2 保留 typed scalar、batch resource 与 exact ACK，但其 release 只能标记为 `BATCH_DECLARED`。
- V3 occurrence release/installation 标记为 `OCCURRENCE_BOUND`；需要真实发生版本的发布比较和精确符号化默认只接受该质量。
- 破坏性变化必须新增 schema、media-type version、batch prefix 和客户端枚举，不能在 V3 下用开关改义。
- V3 message 只能 append 新 field number；item-level ACK 必须在客户端与服务端共同支持的新版本中引入。

2026-08-28，`tools/verify_collector_e2e.py` 使用实际 `HttpApmUploader` 和实际 FastAPI/uvicorn Gateway，经 Gzip 分别发送并重放两个 2-event V2/V3 batch。测试用 SQLite migration 到 server head 后只有 4 个唯一 eventId，并独立核对 exact ACK、V2 typed scalar、V3 occurrence/native frame、低质量 request/batch 声明不覆盖 occurrence、tenant-scoped HMAC/key version 以及 installation 明文不落 `payload_json`。

该结果冻结本地跨语言 wire/持久化闭环，不代表 PostgreSQL 并发与事务、TLS ingress、代理丢 ACK、真实 SigNoz、R8/LLVM、通知、备份恢复、故障注入或 72h soak 已验收。
