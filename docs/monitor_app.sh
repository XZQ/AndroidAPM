#!/usr/bin/env bash
set -euo pipefail

# Sample the AndroidAPM demo process thread count after launch.
PACKAGE_NAME="${PACKAGE_NAME:-com.apm.sample.debug}"
ACTIVITY_NAME="${ACTIVITY_NAME:-com.apm.sample.MainActivity}"
COMPONENT="${PACKAGE_NAME}/${ACTIVITY_NAME}"
DURATION_SEC="${DURATION_SEC:-180}"
INTERVAL_SEC="${INTERVAL_SEC:-1}"
OUTPUT_FILE="${OUTPUT_FILE:-thread_report_$(date +%Y%m%d_%H%M%S).csv}"

command -v adb >/dev/null 2>&1 || {
  echo "adb was not found on PATH" >&2
  exit 1
}

echo "Launching ${COMPONENT}"
adb shell am start -n "$COMPONENT" \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null

PID=""
for _ in $(seq 1 150); do
  PID="$(adb shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
  [ -n "$PID" ] && break
  sleep 0.2
done

if [ -z "$PID" ]; then
  echo "Unable to resolve PID for ${PACKAGE_NAME}" >&2
  exit 1
fi

echo "timestamp,threads" > "$OUTPUT_FILE"
samples=$((DURATION_SEC / INTERVAL_SEC))
for ((i = 0; i < samples; i++)); do
  if ! adb shell "test -d /proc/${PID}" >/dev/null 2>&1; then
    echo "Process ${PID} exited before sampling completed" >&2
    break
  fi

  threads="$(adb shell "ls /proc/${PID}/task 2>/dev/null | wc -l" | tr -d '\r ' )"
  echo "$(date '+%F %T'),${threads}" | tee -a "$OUTPUT_FILE"
  [ "$i" -lt $((samples - 1)) ] && sleep "$INTERVAL_SEC"
done

max_threads="$(awk -F, 'NR > 1 {if ($2 > max) max=$2} END {print max+0}' "$OUTPUT_FILE")"
echo "Report: $(pwd)/${OUTPUT_FILE}"
echo "Maximum sampled thread count: ${max_threads}"

