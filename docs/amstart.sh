#!/bin/bash

# =================配置区域=================
PACKAGE="com.didi.voyager.jarvis.driver"
ACTIVITY=".main.MainActivity"
COMPONENT="$PACKAGE/$ACTIVITY"
# =========================================

echo "========================================"
echo "开始后台+切屏压力测试"
echo "目标: $COMPONENT"
echo "逻辑: 启动 -> 2s -> 后台 -> 5s -> 换屏启动"
echo "按 Ctrl+C 停止"
echo "========================================"

count=1

while true; do
    TIMESTAMP=$(date "+%H:%M:%S")
    echo ""
    echo "[$TIMESTAMP] 第 $count 轮循环开始..."

    # -------------------------------------------------
    # 步骤 1: 在 Display 0 (主屏) 启动
    # -------------------------------------------------
    echo "  1. [Display 0] 启动 Activity..."
    adb shell am start -n $COMPONENT --display 0

    # 启动后等待 2秒 (模拟用户看了一眼)
    sleep 2

    # -------------------------------------------------
    # 步骤 2: 置为后台 (模拟按 Home 键)
    # 这会触发 onPause -> onStop -> onSaveInstanceState
    # -------------------------------------------------
    echo "  2. [Background] 按 Home 键切后台 (触发保存状态)..."
    adb shell input keyevent 3

    # 在后台停留 5秒
    sleep 5

    # -------------------------------------------------
    # 步骤 3: 在 Display 2 (副屏) 启动
    # 这会触发 Relaunch (ConfigChange)
    # -------------------------------------------------
    echo "  3. [Display 2] 切换到副屏启动..."
    adb shell am start -n $COMPONENT --display 2

    # 启动后等待 2秒
    sleep 2

    # -------------------------------------------------
    # 步骤 4: 再次置为后台
    # -------------------------------------------------
    echo "  4. [Background] 按 Home 键切后台..."
    adb shell input keyevent 3

    # 在后台停留 5秒，准备下一轮
    sleep 5

    ((count++))
done