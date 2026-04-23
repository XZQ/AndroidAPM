#!/bin/bash

# --- 配置信息 ---
PACKAGE_NAME="com.didi.voyager.jarvis.driver"
# 您提供的启动命令
START_COMMAND="adb shell am start -n \"com.didi.voyager.jarvis.driver/com.didi.voyager.jarvis.driver.main.MainActivity\" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
DURATION_SEC=180      # 监控3分钟
OUTPUT_FILE="thread_report_$(date +%Y%m%d_%H%M%S).txt"
# ----------------

echo "================================================="
echo "🚀 准备启动 App 并监控线程..."
echo "📂 输出文件: $OUTPUT_FILE"
echo "⏳ 监控时长: $DURATION_SEC 秒"
echo "================================================="

# 1. 启动 App
echo "1. 正在发送启动指令..."
eval $START_COMMAND

# 2. 等待并获取 PID (循环直到拿到进程ID)
echo "2. 正在等待进程初始化..."
PID=""
while [ -z "$PID" ]; do
    PID=$(adb shell pidof $PACKAGE_NAME)
    if [ -z "$PID" ]; then
        sleep 0.2 # 缩短等待间隔，尽可能抓取启动初期的线程
    fi
done

echo "✅ 发现进程 PID: $PID"
echo "3. 开始记录线程数据..."
echo "时间         线程数" | tee -a "$OUTPUT_FILE"
echo "-------------------" | tee -a "$OUTPUT_FILE"

# 3. 执行监控循环
# 使用 adb shell 内部循环以减少通信延迟
adb shell "
p=$PID;
i=0;
while [ \$i -lt $DURATION_SEC ]; do
    # 检查进程是否还活着
    if [ ! -d /proc/\$p ]; then
        echo \"\$(date +%T) 进程已退出\"
        break
    fi

    # 统计线程数
    count=\$(ls /proc/\$p/task | wc -l)
    echo \"\$(date +%T)    \$count\"

    sleep 1
    i=\$((i+1))
done" | tee -a "$OUTPUT_FILE"

echo "================================================="
echo "✅ 监控完成！"
echo "数据已保存至: $(pwd)/$OUTPUT_FILE"
# 自动统计最大值
max_threads=$(awk 'NR>2 {print $2}' "$OUTPUT_FILE" | sort -rn | head -1)
echo "📈 期间最大线程峰值: $max_threads"
echo "================================================="