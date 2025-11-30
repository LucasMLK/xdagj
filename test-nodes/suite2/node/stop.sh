#!/bin/bash

echo "========================================="
echo "Stopping XDAG node 2"
echo "========================================="

# Load PID
if [ -f "xdag.pid" ]; then
    PID=$(cat xdag.pid)

    # Check whether the process exists
    if ps -p $PID > /dev/null 2>&1; then
        echo "Stopping node (PID: $PID)..."
        kill $PID

        # BUG-STORAGE-002 fix: Wait up to 10 seconds for graceful shutdown
        # This allows RocksDB to flush WAL properly before force kill
        # Wait for process termination
        for i in {1..10}; do
            if ! ps -p $PID > /dev/null 2>&1; then
                echo "✅ Node stopped gracefully"
                rm xdag.pid
                exit 0
            fi
            sleep 1
        done

        # Force kill if the process is still alive
        echo "Force-stopping node..."
        kill -9 $PID
        rm xdag.pid
        echo "✅ Node was killed forcefully"
    else
        echo "❌ Node is not running (PID $PID is gone)"
        rm xdag.pid
    fi
else
    echo "❌ xdag.pid file not found"
fi
