# apm-remote-config 模块架构

> 同步日期：2026-07-16

## 1. 职责与依赖

`apm-remote-config` 是 AndroidAPM 控制面客户端，不是通用配置中心：

- 认证 HTTPS GET `/v1/config`
- app/environment/installation 身份与 ETag/304
- 服务端 canonical JSON 的 Ed25519 签名验证
- app-private last-known-good（LKG）缓存
- revision rollback 与同 revision equivocation 防护
- 基于服务端 Date + `elapsedRealtime` 的过期判断
- `ManagedDynamicConfigProvider` 生命周期和可信视图变更通知

依赖方向：

```text
apm-remote-config
  -> api apm-core (DynamicConfigProvider / ApmExecutors / diagnostics boundary)
  -> implementation apm-uploader (HttpHeaderProvider)
  -> implementation Gson (bounded JSON parse)
  -> implementation Tink Android (Ed25519 verify)
```

客户端 minSdk 24，而 Android `java.security.Signature` 对 Ed25519 的平台保证从 API 33 才开始；因此使用[官方支持 Android API 24+ 的 Tink](https://developers.google.com/tink/setup/java)，不维护自写密码算法。平台算法范围见 [Android Signature API](https://developer.android.com/reference/java/security/Signature)。

## 2. 拉取协议

请求 Header：

- `Authorization`：逐请求 `HttpHeaderProvider`，由宿主短期 Token 管理器提供
- `X-APM-App-Id`
- `X-APM-Environment`
- `X-APM-Installation-Id`：匿名、app-private、稳定 rollout 标识
- `If-None-Match`：已有可信缓存时发送

响应：

- `200`：有签名配置，完整校验并同步持久化后发布
- `204`：服务端明确无配置，停用当前值但保留最高 revision 与原可信文档
- `304`：仅在存在 active 可信缓存时接受，更新可信时间锚点
- 其他状态/网络异常：失败，沿用尚未过期的 LKG

默认连接/读取超时为 10/15 秒，JSON 上限 256 KiB；生产 endpoint 只接受 HTTPS。`http://127.0.0.1` 仅用于本地测试。

## 3. 签名与 canonical JSON

服务端 envelope 必须包含：

```text
revision / issuedAt / expiresAt / rolloutBasisPoints
payload / keyId / signature
```

客户端移除 `signature` 后，对其余所有 root 字段递归执行确定性 canonical JSON：对象 key 排序、无额外空白、UTF-8 Unicode 原样输出、数组保序、拒绝非有限数字。这样未来增加签名字段时客户端不会错误忽略未验证内容。

`keyId` 只选择 APK 内置公钥；响应不能新增信任根。公钥输入是标准 Base64 编码的 32 字节原始 Ed25519 key，Tink keyset 使用 RAW 前缀验证服务端 64 字节 detached signature。未知 key、非法 Base64、签名变更或正文变更均拒绝。

## 4. 可信状态机

```text
startup
  -> read SharedPreferences cache
  -> parse + re-verify signature + compare highestRevision
  -> publish only active and non-expired LKG

refresh 200
  -> bounded parse
  -> signature verify
  -> revision >= highestRevision
  -> same revision must keep identical signature
  -> synchronous app-private cache commit
  -> atomically publish immutable state

expiry / 204 / invalid response
  -> getters return caller defaults when no active trusted view
  -> highestRevision remains durable rollback floor
```

同 revision 不同有效签名被视为 publisher equivocation，因为服务端 revision 是不可变记录。缓存写失败时不发布内存状态，避免重启后回退到旧 revision。网络失败不延长 expiry；304 才会用响应 Date 更新锚点。

设备未重启时，可信当前时间为“服务器 Date + elapsedRealtime 增量”，不受用户修改墙上时钟影响。重启导致单调时钟回退时才使用 wall clock；过期比较为 fail closed。

## 5. Core 生命周期

`SignedRemoteConfigProvider` 实现 `ManagedDynamicConfigProvider`：

1. 构造时同步加载并重新验签本地 LKG，不做网络。
2. `Apm.init` 发布完整 runtime state 后调用 `start`。
3. 使用 `ApmExecutors` 单线程 scheduler 立即刷新，之后 fixed delay；默认 15 分钟。
4. 新可信 revision、204 或过期导致有效 fingerprint 变化时回调 core。
5. core 在 `initLock` 下重新评估全局/模块开关并停止或恢复模块。
6. `Apm.stop` 先停止 provider，再拆卸模块和 dispatcher。

## 6. 自动消费的签名键

| 键 | 运行时消费者 |
|---|---|
| `apm.enabled` | `Apm.shouldModuleRun` 全局 kill switch |
| `apm.module.<module>.enabled` | 单模块生命周期 |
| `apm.sampling.default_basis_points` | dispatcher 默认采样 |
| `apm.sampling.<module>[.<event>].basis_points` | 模块/事件采样覆盖 |
| `apm.rate_limit.default_events_per_window` / `default_window_ms` | dispatcher 默认限流 |
| `apm.rate_limit.<module>[.<event>].events_per_window` / `window_ms` | 模块/事件限流覆盖 |
| `apm.upload.endpoint` | opt-in HTTP uploader 地址轮换 |

采样基于稳定 `eventId` hash；ERROR/FATAL 绕过采样和限流。endpoint 覆盖默认关闭，开启后仍只允许无 user-info 的 HTTPS URL。服务端 `rolloutBasisPoints` 已在返回配置前按 `installationId` 稳定分流，客户端保留该值用于签名完整性与审计，不重复执行服务端 rollout。

## 7. 故障与隐私边界

- 不记录 Authorization、配置 payload、签名、公钥正文或 installationId。
- 所有错误只输出稳定诊断码和异常类型。
- provider/transport 只捕获 recoverable `Exception`，不吞 fatal VM error。
- Token provider 每请求执行；失败不复用旧 Token。
- SharedPreferences 使用 application-private mode；它是完整性验签后的可用性缓存，不替代 Android Keystore 中的宿主 Token 存储。
- installationId 必须是匿名随机标识，不使用 IMEI、Android ID 或广告标识。

## 8. 测试

- canonical JSON 精确字节、Unicode 与数值边界
- JDK Ed25519 生成签名，Tink Android verifier 验证 raw key/signature
- HTTP identity/auth/ETag、304、响应上限
- verified 200、204、network LKG、可信 expiry
- rollback、同 revision equivocation、cache re-verification
- core kill switch 停止/恢复与 managed provider shutdown
- 全局/模块/事件采样和限流 precedence/bounds
- uploader 短期 Token 刷新、Header 注入拒绝和 HTTPS endpoint 轮换
