# AndroidAPM Collector Wire Protocol V2

> 状态：客户端协议已冻结｜同步日期：2026-07-31｜参考服务端已联调，生产部署仍位于仓库边界之外

## 1. 适用范围

本协议对应 `SerializationFormat.PROTOBUF_ENVELOPE_V2`。它解决 legacy Line Protocol 和“4 字节长度 + 裸事件 Protobuf”没有 schema/SDK/resource/batch ACK 的问题。旧 `LINE_PROTOCOL`、`PROTOBUF` 保留用于兼容，不被静默解释成 V2。

正式 HTTP 接入使用 `ApmRuntimeProfile.PRODUCTION_STRICT` 时，默认 uploader 必须选择 V2；显式 custom uploader 自行定义并承担等价协议责任。

## 2. 请求

- Method：`POST`
- Content-Type：`application/x-protobuf; message=ApmBatchEnvelope; version=2`
- Content-Encoding：可选 `gzip`
- Body：`apm-model/src/main/proto/apm_event.proto` 的 `ApmBatchEnvelope`

协议 Header：

| Header | 值 |
|---|---|
| `X-Apm-Schema-Version` | `2` |
| `X-Apm-Sdk-Version` | 客户端制品版本，当前 `0.1.0` |
| `X-Apm-Batch-Id` | 与 body 相同的稳定 batch ID |
| `X-Apm-Event-Count` | 本物理请求中的完整事件数 |

这些 Header 由 transport 拥有，静态/动态宿主 Header 不能覆盖。

## 3. Batch envelope

`ApmBatchEnvelope` 固定包含：

1. `schema_version`
2. `sdk_name`
3. `sdk_version`
4. `batch_id`
5. `sent_at_ms`
6. `resource`
7. 按上传顺序排列的 `events`

`sent_at_ms` 是本次请求创建的 epoch 毫秒，可在重试时变化；它不参与幂等身份。

### Batch ID

`batch_id` 对相同有序 event set 跨重试稳定：

```text
"b2-" + hex(first16bytes(SHA-256(
  schemaVersionByte ||
  int32be(len(eventId1Utf8)) || eventId1Utf8 ||
  int32be(len(eventId2Utf8)) || eventId2Utf8 || ...
)))
```

顺序和长度前缀都参与摘要，避免字符串连接歧义。batch ID 用于请求级 ACK；事件级最终幂等仍以每条 `event_id` 为准。

## 4. Standard resource

V2 不接受任意 resource attribute map，只传四个经宿主明确提供的固定字段：

- `service_name`
- `service_version`
- `deployment_environment`
- `installation_id`

strict 模式要求四项非空、无首尾空白、每项最多 256 个 UTF-16 字符。`installation_id` 必须是宿主生成的匿名稳定安装标识；不得放 user ID、广告 ID、Token、手机号或其他直接身份信息。

## 5. Typed event fields

legacy `ApmEventMessage.fields = 10` 保持 `map<string,string>`，只用于旧 standalone Protobuf。V2 envelope 中的 event 不写 field 10，而写 append-only field 15：`map<string, ApmTypedValue> typed_fields`。

| Type | Canonical value |
|---|---|
| `NULL` | value 字段缺省 |
| `STRING` | 原 UTF-8 文本；不支持对象安全降级到 `toString()` |
| `BOOLEAN` | `true` / `false` |
| `BYTE` / `SHORT` / `INT` / `LONG` | 十进制整数 |
| `FLOAT` / `DOUBLE` | Kotlin canonical text，包括明确的非有限值文本 |
| `CHAR` | 一个 UTF-16 code unit |
| `BIG_INTEGER` | 十进制整数 |
| `BIG_DECIMAL` | `toPlainString()` 十进制文本 |

Collector 必须按 `type` 解析，不得猜测字符串类型。未知 type 必须记录到具体 eventId，并让本物理 batch 整体失败且不返回 ACK；V2 不允许把含未知类型的批次伪装成完整接受。

## 6. 大小语义

- `maxUploadBatchBytes` 默认 1 MiB，绝对上限 4 MiB。
- 预算针对 gzip 前的完整 protobuf envelope 字节数，包含 resource 和 envelope 开销。
- uploader 按实际编码结果拆成多个物理 batch，每个物理 batch 独立 ACK。
- strict 要求该预算至少比 `maxEventPayloadBytes` 多 16 KiB envelope headroom。
- 单事件 envelope 超预算时，在打开网络连接前失败，返回未接受；durable outbox 保留并按既有 retry/prune 策略处理。

如果前一物理 batch 已 ACK、后一 batch 失败，调用方仍返回失败，outbox 会重发整个逻辑 claim。Collector 必须按 `event_id` 去重，因此语义仍为 acknowledged at-least-once。

## 7. ACK

V2 的 HTTP 2xx 不是充分成功条件。Collector 必须在同一响应中返回：

| Header | 验收条件 |
|---|---|
| `X-Apm-Schema-Version` | 精确等于 `2` |
| `X-Apm-Batch-Id` | 精确等于请求 batch ID |
| `X-Apm-Event-Count` | 精确等于请求事件数 |

三项全部匹配才表示整批接受，客户端才允许 ACK/delete durable rows。缺失、格式错误、batch ID 不同、事件数不同、非 2xx 都返回失败并保留 outbox。V2 不支持 partial ACK；服务端若只接受部分事件，必须把请求视为失败，且不得返回完整 ACK。

`apm_event.proto` 同时定义等价的 `ApmBatchAck` 消息，供未来响应 body 协商；当前 V2 客户端以 Header 为唯一 ACK 来源，避免响应 body 大小和解析路径产生歧义。

## 8. 兼容与演进

- 现有 Line/legacy Protobuf collector 不会自动兼容 envelope V2，必须显式部署 V2 endpoint。
- V2 message 只能 append 新 field number，不能复用或改变现有字段语义。
- 破坏性变化必须新增 schema version、media-type version 与配置枚举，不能在 V2 下静默切换。
- Collector 仍需实现鉴权、租户隔离、eventId 幂等、协议错误指标和死信；这些不属于客户端仓库。

2026-07-31，独立 `AndroidAPM-Server` 仓库的 `codex/collector-v2-e2e` 分支已实现 V2 decoder、typed scalar、resource/header 一致性、提交后精确 ACK 和 `(tenant_id,event_id)` 去重。客户端仓库的 `tools/verify_collector_e2e.py` 会构建真实 `HttpApmUploader`，经 Gzip HTTP 向运行中的 Gateway 连续重放同一 batch，并从测试用 SQLite 核对唯一事件、类型和 resource。该证据冻结了跨语言 wire 兼容性；它不代表 PostgreSQL 并发/事务、TLS ingress、反向代理丢 ACK、SigNoz 或生产部署已经验收。
