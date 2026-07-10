# apm-io 模块

> 同步日期：2026-07-10｜模块名：`io`

## 目的与接入层次

### Java wrapper

`IoModule.wrapInputStream` / `wrapOutputStream` 返回代理流，自动把 read/write/close 交给 `NativeIoHook` 统计。也可手动调用 `onRead`, `onClose`, `onBufferCopy`, `onIoOperation`。

### Native PLT Hook

`enableNativePltHook=true` 时尝试加载 `libapm-io.so`。C 层运行时 `dlopen/dlsym` 解析 `libxhook.so` 并 hook open/openat/read/write/close；xhook 缺失或安装失败自动回退 Java 路径。

Native target 设置 16 KiB page-size linker alignment；JNI 静态方法绑定有 contract test。

## 检测

- main-thread/slow IO
- small buffer 与 duplicate read
- read/write throughput 聚合
- FD 数量与 `/proc/self/fd` 路径
- Closeable PhantomReference 泄漏
- buffer-copy chain 与可选 zero-copy opportunity

事件：`io_issue`, `io_small_buffer`, `io_duplicate_read`, `io_main_thread`, `io_closeable_leak`, `io_fd_leak`, `io_zero_copy_opportunity`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor/auto hook | 开 |
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

FD monitor 和 ReferenceQueue monitor 是 daemon/background 线程；throughput/path map 有窗口和清理逻辑。主线程回调快速构造事件，core 负责异步落盘。

## 边界

- Java wrapper 只覆盖实际包装的流。
- Native path 需要 ABI 库和可解析的 xhook，不是独立完整 hook engine。
- FD 扫描与 PhantomReference 是启发式，可能受 GC 时机/OEM `/proc` 权限影响。
- 零拷贝事件是优化建议，不证明业务可以无条件改用 `transferTo/sendfile`。

## 测试

Config、module、NativeIoHookInstaller 降级和 JNI contract 有测试；真实 PLT hook、ABI、16 KiB page 与文件系统行为需真机测试。
