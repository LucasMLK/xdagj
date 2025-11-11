#!/bin/bash

echo "========================================="
echo "停止 XDAG 节点 2"
echo "========================================="

# 读取 PID
if [ -f "xdag.pid" ]; then
    PID=$(cat xdag.pid)

    # 检查进程是否存在
    if ps -p $PID > /dev/null 2>&1; then
        echo "正在停止节点 (PID: $PID)..."
        kill $PID

        # 等待进程结束
        for i in {1..10}; do
            if ! ps -p $PID > /dev/null 2>&1; then
                echo "✅ 节点已停止"
                rm xdag.pid
                exit 0
            fi
            sleep 1
        done

        # 如果还没停止，强制杀死
        echo "强制停止节点..."
        kill -9 $PID
        rm xdag.pid
        echo "✅ 节点已强制停止"
    else
        echo "❌ 节点未运行 (PID: $PID 不存在)"
        rm xdag.pid
    fi
else
    echo "❌ 未找到 xdag.pid 文件"
fi
