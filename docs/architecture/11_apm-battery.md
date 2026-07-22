# apm-battery 模块

> 同步日期：2026-07-10｜模块名：`battery`

## 目的与真实接入

`BatteryModule` 自动监听电量变化并定时采样进程 CPU Jiffies。WakeLock、GPS 和 Alarm 不会被通用系统 Hook 自动捕获，宿主需调用：

- `onWakeLockAcquired(tag)` / `onWakeLockReleased(tag)`
- `onGpsStarted(tag)` / `onGpsStopped(tag)`
- `onAlarmScheduled()`

`enableWakeLockHook` 是是否消费这些 callback 的开关，不代表 SDK 自动拦截 PowerManager。

## 检测

- 电量窗口下降超过阈值
- `/proc/self/stat` 进程 CPU tick 与 wall-clock/clock-tick-Hz 计算
- CPU 连续高占用
- WakeLock 持有时间/仍持有
- GPS 使用时间/仍活跃
- 60s 窗口 Alarm 调度洪泛

事件：`battery_drain`, `cpu_high_usage`, `wakelock_held_too_long`, `wakelock_still_held`, `gps_used_too_long`, `gps_still_active`, `alarm_schedule_flood`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| check interval | 60s |
| battery drain | 5% |
| WakeLock threshold | 60s |
| GPS threshold | 30s |
| CPU threshold/sustained | 80% / 30s |
| alarm flood | 12 per window |
| WakeLock/GPS/Alarm/CPU | 全开 |

## 线程与资源

scheduled background executor 周期检查；BroadcastReceiver 接收电量变化；CPU sampler 读取 proc。活动 tag/时间戳用并发集合保存并定期清理。

## 边界

- 没有宿主 callback 就没有 WakeLock/GPS/Alarm 事件。
- CPU 是进程近似，tick Hz 获取失败回退 100。
- 电量变化受充电、系统广播粒度和设备统计影响。
- 不能替代 Battery Historian/Perfetto 系统级归因。

## 测试

Config、模块 callback/窗口和 `CpuJiffiesSampler` 数学有测试；真实电量广播/proc/OEM 行为需设备验证。

## 时间语义

WakeLock/GPS/Alarm/电量变化窗口和 CPU jiffies 采样间隔使用 `ApmClock` 单调时间；事件 timestamp 保持 epoch，避免手工校时扭曲功耗持续时间。
