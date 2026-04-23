#!/bin/bash
set -euo pipefail

# --- 配置 ---
PACKAGE_NAME="com.didi.voyager.jarvis.driver"
ACTIVITY="com.didi.voyager.jarvis.driver/com.didi.voyager.jarvis.driver.main.MainActivity"

DURATION_SEC=120         # 总监控时长
INTERVAL_SEC=30          # 采样间隔
KILL_WAIT_SEC=2          # force-stop/start 后等待
TOP_N=5                  # 每次输出 Top N CPU 线程

OUT_DIR="thread_reports"
TS="$(date +%Y%m%d_%H%M%S)"
RAW_FILE="$OUT_DIR/topH_raw_${TS}.log"
CSV_FILE="$OUT_DIR/thread_trend_${TS}.csv"
SUMMARY_FILE="$OUT_DIR/summary_${TS}.txt"
# ------------

mkdir -p "$OUT_DIR"

# 取线程数：优先 Threads 行，否则 /proc fallback
get_thread_count() {
  local top_out="$1"
  local c
  c="$(echo "$top_out" | grep -m1 '^Threads:' | awk '{print $2}' || true)"
  if [ -n "${c:-}" ]; then
    echo "$c"
  else
    adb shell "ls /proc/$PID/task 2>/dev/null | wc -l" | tr -d '\r' | awk '{print $1}'
  fi
}

# 取 Top N 线程（依赖 top 本身已按 CPU 排序：从表头后取前 N 行）
extract_topN() {
  local top_out="$1"
  echo "$top_out" | awk '
    BEGIN{data=0}
    /(^ *TID )|(^ *PID )/ {data=1; next}
    data==1 {print}
  ' | head -n "$TOP_N"
}

echo "================================================="
echo "🚀 线程增长趋势自动分析（top -H 每 ${INTERVAL_SEC}s）"
echo "📦 Package: $PACKAGE_NAME"
echo "📂 RAW:     $RAW_FILE"
echo "📊 CSV:     $CSV_FILE"
echo "🧾 SUMMARY: $SUMMARY_FILE"
echo "================================================="

echo "[1/4] 强制停止进程..."
adb shell am force-stop "$PACKAGE_NAME" || true
sleep "$KILL_WAIT_SEC"

echo "[2/4] 启动 App..."
adb shell am start -n "$ACTIVITY" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null
sleep "$KILL_WAIT_SEC"

echo "[3/4] 获取 PID..."
PID=""
for _ in {1..150}; do
  PID="$(adb shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
  [ -n "$PID" ] && break
  sleep 0.2
done
if [ -z "$PID" ]; then
  echo "❌ 获取 PID 失败，退出。"
  exit 1
fi
echo "✅ PID: $PID"

# 初始化文件头
{
  echo "================================================="
  echo "Package: $PACKAGE_NAME"
  echo "Activity: $ACTIVITY"
  echo "PID: $PID"
  echo "Start: $(date '+%F %T')"
  echo "Duration: ${DURATION_SEC}s, Interval: ${INTERVAL_SEC}s"
  echo "================================================="
} | tee -a "$RAW_FILE" >/dev/null

echo "timestamp,threads,delta,monotonic_non_decreasing,top1_tid,top1_thread" > "$CSV_FILE"

ITERATIONS=$((DURATION_SEC / INTERVAL_SEC))

prev=""
monotonic=1
max_threads=0
min_threads=999999
sum_threads=0

max_delta=-999999
max_delta_at=""
max_delta_sample_idx=-1

series=""

echo "[4/4] 开始采样并自动分析..."
for ((i=0; i<ITERATIONS; i++)); do
  TS_NOW="$(date '+%F %T')"

  if ! adb shell "[ -d /proc/$PID ]" >/dev/null 2>&1; then
    echo "❌ $TS_NOW 进程已退出（PID=$PID）" | tee -a "$RAW_FILE"
    break
  fi

  # 只采样一次 top，后续解析都基于同一份输出，避免“漂”
  TOP_OUT="$(adb shell top -H -p "$PID" -b -n 1 2>/dev/null | sed 's/\r$//')"
  THREADS="$(get_thread_count "$TOP_OUT")"
  THREADS="${THREADS:-0}"

  # delta（首次采样无上一次，输出 N/A）
  delta_str="N/A"
  delta_num=0
  if [ -n "$prev" ]; then
    delta_num=$((THREADS - prev))
    if [ "$THREADS" -lt "$prev" ]; then
      monotonic=0
    fi
    if [ "$delta_num" -ge 0 ]; then
      delta_str="+$delta_num"
    else
      delta_str="$delta_num"
    fi
  fi
  prev="$THREADS"

  # 统计
  [ "$THREADS" -gt "$max_threads" ] && max_threads="$THREADS"
  [ "$THREADS" -lt "$min_threads" ] && min_threads="$THREADS"
  sum_threads=$((sum_threads + THREADS))

  # 最大增量（仅在有 prev 的情况下才比较）
  if [ "$delta_str" != "N/A" ] && [ "$delta_num" -gt "$max_delta" ]; then
    max_delta="$delta_num"
    max_delta_at="$TS_NOW"
    max_delta_sample_idx="$i"
  fi

  # 序列
  if [ -z "$series" ]; then
    series="$THREADS"
  else
    series="$series,$THREADS"
  fi

  # Top N CPU
  TOPN="$(extract_topN "$TOP_OUT" || true)"
  top1_tid="$(echo "$TOPN" | head -n1 | awk '{print $1}' || true)"
  top1_thread="$(echo "$TOPN" | head -n1 | awk '{print $(NF-1)}' || true)"

  # 是否标记 peak delta
  marker=""
  if [ "$i" -eq "$max_delta_sample_idx" ] && [ "$delta_str" != "N/A" ]; then
    marker=" 🔥 PEAK_DELTA"
  fi

  {
    echo ""
    echo "================ ${TS_NOW} (sample $((i+1))/$ITERATIONS)${marker} ================"
    echo "$TOP_OUT"
    echo ">> 当前线程总数: $THREADS"
    echo ">> 增量(delta): $delta_str"
    echo ">> Top ${TOP_N} CPU 线程:"
    echo "$TOPN"
  } >> "$RAW_FILE"

  # CSV 里的 delta 用数值（首次用空更干净）
  if [ "$delta_str" = "N/A" ]; then
    echo "$TS_NOW,$THREADS,,${monotonic},${top1_tid:-},${top1_thread:-}" >> "$CSV_FILE"
  else
    echo "$TS_NOW,$THREADS,$delta_num,${monotonic},${top1_tid:-},${top1_thread:-}" >> "$CSV_FILE"
  fi

  if [ $i -lt $((ITERATIONS-1)) ]; then
    sleep "$INTERVAL_SEC"
  fi
done

# 平均值（按实际采样次数估算：用 CSV 行数 - 1）
samples="$(($(wc -l < "$CSV_FILE") - 1))"
[ "$samples" -le 0 ] && samples=1
avg_threads=$((sum_threads / samples))

# summary 的 max_delta 处理：如果从未算出（比如只有 1 次采样），显示 N/A
max_delta_summary="$max_delta"
max_delta_at_summary="$max_delta_at"
if [ "$max_delta_sample_idx" -lt 0 ]; then
  max_delta_summary="N/A"
  max_delta_at_summary="N/A"
fi

{
  echo "================================================="
  echo "Thread Trend Summary"
  echo "Package: $PACKAGE_NAME"
  echo "PID: $PID"
  echo "Samples: $samples"
  echo "Interval(sec): $INTERVAL_SEC"
  echo "-------------------------------------------------"
  echo "Min threads: $min_threads"
  echo "Max threads: $max_threads"
  echo "Avg threads: $avg_threads"
  echo "Max delta(per interval): $max_delta_summary at $max_delta_at_summary"
  echo "Monotonic non-decreasing: $([ "$monotonic" -eq 1 ] && echo YES || echo NO)"
  echo "Thread series: $series"
  echo "-------------------------------------------------"
  echo "RAW log: $RAW_FILE"
  echo "CSV:     $CSV_FILE"
  echo "================================================="
} | tee "$SUMMARY_FILE"

echo "✅ 完成：$(pwd)/$SUMMARY_FILE"