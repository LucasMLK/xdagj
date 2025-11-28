#!/bin/bash

echo "停止 XDAG 节点..."

if [ ! -f "xdag.pid" ]; then
    echo "⚠ 未找到 PID 文件"
    exit 1
fi

PID=$(cat xdag.pid)

if ps -p $PID > /dev/null 2>&1; then
    echo "停止进程 $PID (SIGTERM)..."
    kill $PID

    # BUG-STORAGE-002 fix: Increase wait time from 2s to 10s
    # This allows RocksDB to flush WAL properly before force kill
    echo "等待节点正常关闭 (最多10秒)..."

    for i in {1..10}; do
        sleep 1
        if ! ps -p $PID > /dev/null 2>&1; then
            echo "✅ 节点已正常停止 (用时 ${i} 秒)"
            rm -f xdag.pid
            exit 0
        fi
        echo -n "."
    done

    echo ""
    echo "⚠ 节点未在10秒内停止，强制终止..."
    if ps -p $PID > /dev/null 2>&1; then
        kill -9 $PID
        echo "✅ 节点已强制停止"
    fi
else
    echo "⚠ 进程 $PID 不存在"
fi

rm -f xdag.pid
