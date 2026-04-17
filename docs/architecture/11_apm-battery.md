# apm-battery 模块架构

> 电量监控：WakeLock 追踪 + 电量下降速率 + CPU Jiffies 采样 + Alarm 泛洪

---

## 类图

```
┌──────────────────────────────────────┐
│         BatteryModule                 │
│      (implements ApmModule)           │
├──────────────────────────────────────┤
│ - config: BatteryConfig              │
│ - activeWakeLocks: HashMap<Tag, Time>│
│ - lastBatteryLevel: Int              │
│ - lastBatteryTime: Long              │
│ - batteryReceiver: BroadcastReceiver │
│ - cpuJiffiesSampler: CpuJiffiesSampler│
├──────────────────────────────────────┤
│ + onStart() → 注册电量广播接收器     │
│ + onStop() → 注销接收器              │
│ + onWakeLockAcquired(tag)            │
│ + onWakeLockReleased(tag)            │
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
│  4. Alarm 泛洪 (预留)                              │
│     config.alarmFloodThreshold = 12                │
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
