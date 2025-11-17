#!/bin/bash

echo "停止 XDAG 节点..."

if [ ! -f "xdag.pid" ]; then
    echo "⚠ 未找到 PID 文件"
    exit 1
fi

PID=$(cat xdag.pid)

if ps -p $PID > /dev/null 2>&1; then
    echo "停止进程 $PID..."
    kill $PID
    sleep 2

    if ps -p $PID > /dev/null 2>&1; then
        echo "强制停止..."
        kill -9 $PID
    fi

    echo "✅ 节点已停止"
else
    echo "⚠ 进程 $PID 不存在"
fi

rm -f xdag.pid
