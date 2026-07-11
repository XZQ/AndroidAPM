# apm-io 模块

> 同步日期：2026-07-11｜模块名：`io`

## 目的与接入层次

### Java wrapper

`IoModule.wrapInputStream` / `wrapOutputStream` 返回代理流，自动把 read/write/close 交给 `NativeIoHook` 统计。也可手动调用 `onRead`, `onClose`, `onBufferCopy`, `onIoOperation`。

### Native PLT Hook

`enableNativePltHook=true` 时尝试加载 `libapm-io.so`。C 层运行时 `dlopen/dlsym` 解析 `libxhook.so`，按 xhook 的“调用方 ELF pathname”语义匹配 `.*\.so$` 的 PLT，并忽略 `libapm-io.so`/`libxhook.so` 自身，然后 hook open/openat/read/write/close；xhook 缺失或安装失败只保留宿主显式 wrapper 路径。C 回调使用 thread-local depth 防止 APM 自身 IO 递归；patched/recording 使用 C11 atomic 分离，stop 后 PLT 即使仍存在也只调用原函数，再次 start 不重复注册。Native pthread 仅在未附加时 attach JVM，并通过 pthread TLS destructor detach；非 ASCII、控制字符和字面 `%` 路径字节使用无歧义 `%HH` 编码，截断时追加完整输入的稳定哈希后缀，JNI 构造失败会清除监控异常。

Native target 设置 16 KiB page-size linker alignment；JNI 静态方法绑定有 contract test。

## 检测

- main-thread/slow IO
- small buffer 与 duplicate read
- read/write throughput 聚合
- FD 数量与 `/proc/self/fd` 路径
- Closeable PhantomReference 泄漏
- buffer-copy chain 与可选 zero-copy opportunity

事件：`io_issue`, `io_small_buffer`, `io_duplicate_read`, `io_main_thread`, `io_closeable_leak`, `io_fd_leak`, `io_zero_copy_opportunity`, `io_throughput`。吞吐指标是累计快照，每达到 `throughputWindow` 个操作输出一次。显式 wrapper 在执行底层流调用时设置 ThreadLocal 深度，Native 同步回调不重复统计；wrapper 自己使用 `System.nanoTime` 测量 read/write/flush/close 的真实调用边界，因此文件流不双报、内存/自定义流也不会漏掉慢操作。Native 只把实际计时的 read/write 作为 latency，不把 open→close 会话寿命伪装成 close 耗时。duplicate-read 与 small-buffer 对每个路径只输出一次。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| generic Java auto hook | 关 / deprecated；使用显式 wrapper |
| main-thread/single IO | 50ms / 500ms |
| large buffer | 512 KiB |
| small buffer | 4096 bytes |
| duplicate read | 5 |
| Closeable/FD detection | 开 |
| FD threshold | 500 |
| throughput | 开，window 100 |
| Native PLT hook | 开（可降级） |
| zero-copy detection | 关 |

## 线程与资源

FD monitor 和 ReferenceQueue monitor 是 daemon/background 线程；throughput 通过操作数窗口限制事件频率。duplicate-read/small-buffer/throughput 最多保留 256 个路径，超限吞吐合并到 `<other>`；zero-copy 最多保留 256 条 copy chain，避免高基数路径造成 SDK 内存增长。Native fd session table 由 mutex 与 generation 保护，避免并发读写数据竞争和 close 后 fd 复用串线。主线程回调快速构造事件，core 负责异步落盘。

Java wrapper 在底层 read/write/close 已完成后执行的全部监控 bookkeeping 都有异常隔离；业务看到的结果和异常只来自原始流。

## 边界

- Java wrapper 只覆盖实际包装的流。
- `enableAutoHook` 是弃用兼容字段且默认关闭，不会替换任意 JDK/Android 流实例。
- ThreadLocal 去重只关联同线程同步 syscall；若自定义流把底层 syscall 转交给另一线程，宿主应只启用 wrapper 或 Native 路径之一，避免跨线程双计。
- Native path 需要 ABI 库和可解析的 xhook，不是独立完整 hook engine；失败时只保留宿主已显式安装的 wrapper，不会自动代理任意流。
- FD 扫描与 PhantomReference 是启发式，可能受 GC 时机/OEM `/proc` 权限影响。
- 零拷贝事件是优化建议，不证明业务可以无条件改用 `transferTo/sendfile`。

## 测试

Config、module、NativeIoHookInstaller 降级和 JNI contract 有测试；真实 PLT hook、ABI、16 KiB page 与文件系统行为需真机测试。
