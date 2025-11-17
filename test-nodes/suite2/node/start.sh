#!/bin/bash
cd "$(dirname "$0")"
echo "Stopping old instance if exists..."
if [ -f xdag.pid ]; then
    kill $(cat xdag.pid) 2>/dev/null && sleep 2
fi
echo "Starting Node2 (Devnet)..."
nohup java -Dlog4j.configurationFile=log4j2.xml -Dconfig.file=xdag-devnet.conf -cp .:xdagj.jar io.xdag.Bootstrap -d --password test123 > node2.log 2>&1 &
echo $! > xdag.pid
echo "Node2 started (PID: $(cat xdag.pid))"
echo "Waiting for telnet service..."
sleep 10
echo "Check logs: tail -f logs/xdag-info.log"
echo "Telnet: telnet 127.0.0.1 6002 (password: root)"
