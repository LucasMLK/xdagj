#!/bin/bash
# XDAG Rollback & Consistency Test
# Scenario:
# 1. Start 2 nodes connected. Sync Genesis.
# 2. Partition network (Node 2 isolated).
# 3. Node 1 mines 10+ blocks (Main Chain).
# 4. Node 2 mines 2-3 blocks (Fork Chain).
# 5. Reconnect Node 2.
# 6. Verify Node 2 rolls back Fork Chain and accepts Main Chain.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_NODES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SUITE1_DIR="$TEST_NODES_DIR/suite1"
SUITE2_DIR="$TEST_NODES_DIR/suite2"

# Config Backup
NODE2_CONF="$SUITE2_DIR/node/xdag-devnet.conf"
NODE2_CONF_BAK="$NODE2_CONF.bak"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check dependencies
if ! command -v jq &> /dev/null; then
    error "jq command not found. Please install it (brew install jq)."
    exit 1
fi

# ==================== Control Functions ====================

clean_all() {
    log "Cleaning environment..."
    "$SCRIPT_DIR/stop-all.sh" > /dev/null 2>&1 || true
    rm -rf "$SUITE1_DIR/node/devnet" "$SUITE1_DIR/node/logs"
    rm -rf "$SUITE1_DIR/pool/logs" "$SUITE1_DIR/miner/logs"
    rm -rf "$SUITE2_DIR/node/devnet" "$SUITE2_DIR/node/logs"
    rm -rf "$SUITE2_DIR/pool/logs" "$SUITE2_DIR/miner/logs"
    
    # Restore config if backup exists
    if [ -f "$NODE2_CONF_BAK" ]; then
        mv "$NODE2_CONF_BAK" "$NODE2_CONF"
    fi
}

start_node() {
    local suite="$1"
    local dir="$TEST_NODES_DIR/$suite/node"
    log "Starting Node ($suite)..."
    cd "$dir"
    bash start.sh > /dev/null 2>&1
    
    # Wait for start
    local count=0
    while [ $count -lt 30 ]; do
        if [ -f xdag.pid ] && kill -0 "$(cat xdag.pid)" 2>/dev/null; then
            return 0
        fi
        sleep 1
        ((count++))
    done
    error "Node ($suite) failed to start"
    return 1
}

stop_node() {
    local suite="$1"
    local dir="$TEST_NODES_DIR/$suite/node"
    log "Stopping Node ($suite)..."
    cd "$dir"
    if [ -f xdag.pid ]; then
        kill $(cat xdag.pid) 2>/dev/null || true
        rm -f xdag.pid
    fi
}

start_pool() {
    local suite="$1"
    local dir="$TEST_NODES_DIR/$suite/pool"
    log "Starting Pool ($suite)..."
    cd "$dir"
    nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &
    echo $! > pool.pid
}

stop_pool() {
    local suite="$1"
    local dir="$TEST_NODES_DIR/$suite/pool"
    cd "$dir"
    if [ -f pool.pid ]; then
        kill $(cat pool.pid) 2>/dev/null || true
        rm -f pool.pid
    else
        pkill -f "xdagj-pool.jar" || true
    fi
}

start_miner() {
    local suite="$1"
    local dir="$TEST_NODES_DIR/$suite/miner"
    log "Starting Miner ($suite)..."
    cd "$dir"
    nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
    echo $! > miner.pid
}

stop_miner() {
    local suite="$1"
    local dir="$TEST_NODES_DIR/$suite/miner"
    cd "$dir"
    if [ -f miner.pid ]; then
        kill $(cat miner.pid) 2>/dev/null || true
        rm -f miner.pid
    else
        pkill -f "xdagj-miner.jar" || true
    fi
}

# ==================== Telnet Helper ====================

get_block_height() {
    local port="$1"
    local response=$(curl -s --max-time 2 "http://127.0.0.1:$port/api/v1/blocks/number")
    if [ -z "$response" ]; then
        echo "0"
        return
    fi
    local hex_num=$(echo "$response" | jq -r '.blockNumber')
    if [ "$hex_num" == "null" ] || [ -z "$hex_num" ]; then
        echo "0"
    else
        printf "%d" "$hex_num"
    fi
}

wait_for_height() {
    local port="$1"
    local target="$2"
    local name="$3"
    log "Waiting for $name to reach height $target..."
    
    local count=0
    while [ $count -lt 600 ]; do
        local h=$(get_block_height "$port")
        # Remove any non-digit chars
        h=$(echo "$h" | tr -cd '0-9')
        if [ -n "$h" ] && [ "$h" -ge "$target" ]; then
            success "$name reached height $h"
            return 0
        fi
        sleep 2
        ((count+=2))
        echo -n "."
    done
    echo ""
    error "Timeout waiting for $name height"
    return 1
}

# ==================== The Test ====================

# 1. Setup
clean_all

# Restore Genesis Files
log "Restoring genesis files..."
PROJECT_ROOT="$(cd "$TEST_NODES_DIR/.." && pwd)"
mkdir -p "$SUITE1_DIR/node/devnet"
mkdir -p "$SUITE2_DIR/node/devnet"
cp "$PROJECT_ROOT/config/genesis-devnet.json" "$SUITE1_DIR/node/devnet/"
cp "$PROJECT_ROOT/config/genesis-devnet.json" "$SUITE2_DIR/node/devnet/"

log "Step 1: Initial Setup - Starting Nodes..."
start_node "suite1"
start_node "suite2"

# Wait for P2P
log "Waiting for P2P sync (15s)..."
sleep 15

# Verify Sync
H1=$(get_block_height 10001)
H2=$(get_block_height 10002)
START_H=$H1
log "Height: Node1=$H1, Node2=$H2"

# 2. Isolate Node 2
log "Step 2: Isolating Node 2..."
stop_node "suite2"
cp "$NODE2_CONF" "$NODE2_CONF_BAK"
# Remove whiteIPs config to isolate
sed -i '' 's/node.whiteIPs.*/node.whiteIPs = []/' "$NODE2_CONF"

start_node "suite2"
log "Node 2 restarted (Isolated)"

# 3. Mine Forks
log "Step 3: Mining Forks..."

# Start Mining on Node 1 (Main Chain)
start_pool "suite1"
sleep 5
start_miner "suite1"
log "Miner 1 started (Building Main Chain)"

# Start Mining on Node 2 (Fork Chain)
start_pool "suite2"
sleep 5
start_miner "suite2"
log "Miner 2 started (Building Fork Chain)"

# Target: Node 1 -> Start + 5, Node 2 -> Start + 1
TARGET_H1=$((START_H + 5))
log "Mining in progress... Waiting for Node 1 to reach height $TARGET_H1..."
wait_for_height 10001 $TARGET_H1 "Node 1"

TARGET_H2=$((START_H + 1))
log "Waiting for Node 2 to fork (reach height $TARGET_H2)..."
wait_for_height 10002 $TARGET_H2 "Node 2"

log "Checking Node 2 height..."
H2=$(get_block_height 10002)
H2=$(echo "$H2" | tr -cd '0-9')
log "Node 2 Height (Fork): $H2"

if [ -z "$H2" ] || [ "$H2" -eq 0 ]; then
    warn "Node 2 hasn't mined any blocks yet. Waiting a bit..."
    sleep 30
    H2=$(get_block_height 10002)
fi

log "Stopping Miners..."
stop_miner "suite1"
stop_pool "suite1"
stop_miner "suite2"
stop_pool "suite2"

H1=$(get_block_height 10001)
H1=$(echo "$H1" | tr -cd '0-9')
H2=$(get_block_height 10002)
H2=$(echo "$H2" | tr -cd '0-9')

log "Status before Reconnect:"
log "Node 1 (Main): Height $H1"
log "Node 2 (Fork): Height $H2"

if [ "$H1" -le "$H2" ]; then
    error "Test setup failed: Node 1 height ($H1) must be greater than Node 2 ($H2)"
    exit 1
fi

# 4. Reconnect & Rollback
log "Step 4: Reconnecting Node 2..."
stop_node "suite2"
mv "$NODE2_CONF_BAK" "$NODE2_CONF" # Restore config
start_node "suite2"

log "Waiting for Sync/Rollback (max 300s)..."
# Polling loop
MAX_RETRIES=60
count=0
MATCH=0
while [ $count -lt $MAX_RETRIES ]; do
    FINAL_H1=$(get_block_height 10001)
    FINAL_H2=$(get_block_height 10002)
    FINAL_H1=$(echo "$FINAL_H1" | tr -cd '0-9')
    FINAL_H2=$(echo "$FINAL_H2" | tr -cd '0-9')
    
    if [ -n "$FINAL_H1" ] && [ -n "$FINAL_H2" ] && [ "$FINAL_H1" != "0" ] && [ "$FINAL_H2" != "0" ]; then
        log "Checking: Node1=$FINAL_H1, Node2=$FINAL_H2"
        if [ "$FINAL_H1" == "$FINAL_H2" ]; then
            MATCH=1
            break
        fi
        # Also accept if Node 2 is slightly ahead (mining continues) or behind by 1
        DIFF=$((FINAL_H1 - FINAL_H2))
        ABS_DIFF=${DIFF#-}
        if [ "$ABS_DIFF" -le 1 ] && [ "$FINAL_H2" -ge "$H1" ]; then
             MATCH=1
             break
        fi
    fi
    sleep 5
    ((count++))
done

log "Step 5: Verifying Consistency..."
FINAL_H1=$(echo "$FINAL_H1" | tr -cd '0-9')
FINAL_H2=$(echo "$FINAL_H2" | tr -cd '0-9')

log "Final Heights: Node1=$FINAL_H1, Node2=$FINAL_H2"

if [ "$FINAL_H1" == "$FINAL_H2" ]; then
    success "CONSISTENCY ACHIEVED! Node 2 synced with Node 1."
    if [ "$FINAL_H2" -ge "$H1" ]; then
        success "Rollback confirmed (Height >= Pre-fork Main Height)"
    else
        warn "Height matches but seems low? Check logs."
    fi
else
    error "Consistency Check FAILED. Heights do not match."
    log "Check logs in suite2/node/logs/xdag-info.log"
    exit 1
fi

log "Cleaning up..."
clean_all
success "Test Scenario Completed Successfully"
