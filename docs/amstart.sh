#!/usr/bin/env bash
set -euo pipefail

# AndroidAPM sample multi-display/background stress helper.
# Override these values through environment variables when testing another app.
PACKAGE_NAME="${PACKAGE_NAME:-com.apm.sample.debug}"
ACTIVITY_NAME="${ACTIVITY_NAME:-com.apm.sample.MainActivity}"
COMPONENT="${PACKAGE_NAME}/${ACTIVITY_NAME}"
PRIMARY_DISPLAY="${PRIMARY_DISPLAY:-0}"
SECONDARY_DISPLAY="${SECONDARY_DISPLAY:-2}"
FOREGROUND_SECONDS="${FOREGROUND_SECONDS:-2}"
BACKGROUND_SECONDS="${BACKGROUND_SECONDS:-5}"
ROUNDS="${ROUNDS:-0}" # 0 means run until interrupted.

command -v adb >/dev/null 2>&1 || {
  echo "adb was not found on PATH" >&2
  exit 1
}

echo "AndroidAPM sample lifecycle stress"
echo "Component: ${COMPONENT}"
echo "Displays: ${PRIMARY_DISPLAY} -> ${SECONDARY_DISPLAY}"
echo "Rounds: $([ "$ROUNDS" -eq 0 ] && echo unlimited || echo "$ROUNDS")"

round=1
while [ "$ROUNDS" -eq 0 ] || [ "$round" -le "$ROUNDS" ]; do
  echo "[$(date '+%H:%M:%S')] round ${round}"

  adb shell am start --display "$PRIMARY_DISPLAY" -n "$COMPONENT" \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null
  sleep "$FOREGROUND_SECONDS"
  adb shell input keyevent KEYCODE_HOME
  sleep "$BACKGROUND_SECONDS"

  adb shell am start --display "$SECONDARY_DISPLAY" -n "$COMPONENT" \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null
  sleep "$FOREGROUND_SECONDS"
  adb shell input keyevent KEYCODE_HOME
  sleep "$BACKGROUND_SECONDS"

  round=$((round + 1))
done

