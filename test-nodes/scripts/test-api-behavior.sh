#!/bin/bash
# Simple API Test
set -e

start_node() {
    cd test-nodes/suite1/node
    ./start.sh > /dev/null 2>&1
    cd - > /dev/null
}

stop_node() {
    cd test-nodes/suite1/node
    ./stop.sh > /dev/null 2>&1 || true
    cd - > /dev/null
}

get_height() {
    local response=$(curl -s "http://127.0.0.1:10001/api/v1/blocks/number")
    echo "$response"
}

echo "Starting Node 1..."
stop_node
rm -rf test-nodes/suite1/node/devnet
mkdir -p test-nodes/suite1/node/devnet
cp config/genesis-devnet.json test-nodes/suite1/node/devnet/
start_node

echo "Waiting for start..."
sleep 10

echo "Initial Height:"
get_height

echo "Starting Miner..."
cd test-nodes/suite1/pool
nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &
cd - > /dev/null

echo "Waiting for pool to initialize..."
sleep 5

cd test-nodes/suite1/miner
nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
cd - > /dev/null

echo "Mining for 15s..."
sleep 15

echo "Height after mining:"
get_height

echo "Cleaning up..."
stop_node
pkill -f xdagj-pool.jar
pkill -f xdagj-miner.jar
