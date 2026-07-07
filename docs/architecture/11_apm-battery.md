# apm-battery 模块架构

> 电量监控：电量下降 + CPU Jiffies + 宿主回调接入的 WakeLock/GPS/Alarm

## 2026-07-07 优化更新

- CPU 时钟频率改由 `Os.sysconf(_SC_CLK_TCK)` 读取（原硬编码 100 Hz 在非 100 Hz 内核上有系统性偏差）；使用率语义明确为单核占用分数（1.0=占满一核，上限核数）；换算数学由注入 seam 的单测在 100/250 Hz 下锁定。
- /proc/self/stat 读取失败经 `Apm.recordInternalError` 计数。

## 2026-06-15 实现状态

- CPU Jiffies sampler 已由周期任务实际调用并输出持续高 CPU 事件。
- WakeLock、GPS 和 AlarmManager 无稳定公共全局 hook，因此提供 `onWakeLockAcquired/Released`、`onGpsStarted/Stopped`、`onAlarmScheduled` 给宿主包装层调用。
- Alarm 泛洪按 `checkIntervalMs` 滑动窗口计数，达到 `alarmFloodThreshold` 后上报并清空当前窗口。
- 活跃集合使用并发容器，停止模块时统一清理。

---

## 类图

```
┌──────────────────────────────────────┐
│         BatteryModule                 │
│      (implements ApmModule)           │
├──────────────────────────────────────┤
│ - config: BatteryConfig              │
│ - activeWakeLocks: ConcurrentHashMap │
│ - activeGpsSessions: ConcurrentHashMap│
│ - alarmTimestamps: ConcurrentLinkedQueue│
│ - lastBatteryLevel: Int              │
│ - lastBatteryTime: Long              │
│ - batteryReceiver: BroadcastReceiver │
│ - cpuJiffiesSampler: CpuJiffiesSampler│
├──────────────────────────────────────┤
│ + onStart() → 注册电量广播接收器     │
│ + onStop() → 注销接收器              │
│ + onWakeLockAcquired(tag)            │
│ + onWakeLockReleased(tag)            │
│ + onGpsStarted(tag) / onGpsStopped(tag)│
│ + onAlarmScheduled()                 │
│ + onBatteryLevelChanged(percent)     │
│ + checkWakeLocks()  → 定期检查       │
│ + checkBatteryDrain() → 检测电量下降 │
└──────────────────────────────────────┘

┌─────────────────────────┐
│   CpuJiffiesSampler     │
├─────────────────────────┤
│ - lastProcessJiffies    │
│ - lastSampleTime        │
│ - highCpuSince: Long    │
│ - onCpuHigh: callback   │
├─────────────────────────┤
│ + start() / stop()      │
│ + sample()              │
│   └── read /proc/self/stat│
│       ├── utime (index 13)│
│       └── stime (index 14)│
└─────────────────────────┘
```

## 检测流程

```
┌────────────────────────────────────────────────────┐
│              电量监控检测维度                        │
├────────────────────────────────────────────────────┤
│                                                    │
│  1. WakeLock 超时                                  │
│     onWakeLockAcquired(tag) → activeWakeLocks[tag]=now│
│     checkWakeLocks() → 持有时间 >= 60s             │
│     → emit("wakelock_held", WARN)                  │
│                                                    │
│  2. 电量快速下降                                   │
│     onBatteryLevelChanged(percent)                 │
│     drainRate = (old - new) / timeDiff             │
│     if (drain >= 5%/interval)                      │
│     → emit("battery_drain", WARN)                  │
│                                                    │
│  3. CPU 持续高占用                                 │
│     CpuJiffiesSampler 定时采样                     │
│     if (cpuPercent >= 80% 持续 30s)                │
│     → emit("cpu_high_usage", WARN)                 │
│                                                    │
│  4. GPS/Alarm 宿主回调                             │
│     GPS 超时 → gps_used_too_long/gps_still_active │
│     Alarm 窗口计数超阈值 → alarm_schedule_flood    │
│                                                    │
└────────────────────────────────────────────────────┘
```

## CPU Jiffies 采样原理

```
┌──────────────────────────────────────────────────┐
│           /proc/self/stat CPU 使用率计算          │
├──────────────────────────────────────────────────┤
│                                                  │
│  读取 /proc/self/stat 文件                       │
│  格式: pid (comm) state utime stime ...          │
│                                                  │
│  utime: 用户态 CPU 时间 (index 13, 单位 jiffies) │
│  stime: 内核态 CPU 时间 (index 14, 单位 jiffies) │
│                                                  │
│  CPU% = (ΔprocessJiffies / ΔtotalJiffies) × 100 │
│                                                  │
│  1 jiffie ≈ 10ms (取决于 CONFIG_HZ)              │
│                                                  │
│  高 CPU 检测:                                     │
│  ├── cpuPercent >= cpuThresholdPercent (80%)     │
│  ├── 持续 >= cpuSustainedSeconds (30s)           │
│  └── 触发回调 onCpuHigh(percent, durationSec)    │
│      → BatteryModule.emit(ALERT)                 │
│                                                  │
└──────────────────────────────────────────────────┘
```
