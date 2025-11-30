#!/bin/bash

echo "Stopping XDAG node..."

if [ ! -f "xdag.pid" ]; then
    echo "⚠ PID file not found"
    exit 1
fi

PID=$(cat xdag.pid)

if ps -p $PID > /dev/null 2>&1; then
    echo "Stopping process $PID (SIGTERM)..."
    kill $PID

    # BUG-STORAGE-002 fix: Increase wait time from 2s to 10s
    # This allows RocksDB to flush WAL properly before force kill
    echo "Waiting for a graceful shutdown (up to 10s)..."

    for i in {1..10}; do
        sleep 1
        if ! ps -p $PID > /dev/null 2>&1; then
            echo "✅ Node stopped gracefully (took ${i}s)"
            rm -f xdag.pid
            exit 0
        fi
        echo -n "."
    done

    echo ""
    echo "⚠ Node did not stop within 10s, forcing termination..."
    if ps -p $PID > /dev/null 2>&1; then
        kill -9 $PID
        echo "✅ Node was killed forcefully"
    fi
else
    echo "⚠ Process $PID does not exist"
fi

rm -f xdag.pid
