#!/bin/bash

# XDAG Test Environment - Start All Services
# Starts Suite1 and Suite2 (Node + Pool + Miner)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NODES_DIR="$(dirname "$SCRIPT_DIR")"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Start a node
start_node() {
    local suite=$1
    local node_name=$2

    log_info "Starting $node_name..."

    cd "$TEST_NODES_DIR/$suite/node"

    if [ -f "xdagj.pid" ]; then
        local pid=$(cat xdagj.pid)
        if ps -p $pid > /dev/null 2>&1; then
            log_warn "$node_name already running (PID: $pid)"
            return 0
        fi
    fi

    ./start.sh
    sleep 2

    if [ -f "xdagj.pid" ]; then
        log_success "$node_name started"
    else
        log_error "Failed to start $node_name"
        return 1
    fi
}

# Start a pool
start_pool() {
    local suite=$1
    local pool_name=$2
    local port=$3

    log_info "Starting $pool_name..."

    cd "$TEST_NODES_DIR/$suite/pool"

    # Check if already running
    if lsof -i :$port > /dev/null 2>&1; then
        log_warn "$pool_name already running on port $port"
        return 0
    fi

    nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &
    local pid=$!
    echo $pid > pool.pid
    sleep 2

    if ps -p $pid > /dev/null 2>&1; then
        log_success "$pool_name started (PID: $pid)"
    else
        log_error "Failed to start $pool_name"
        return 1
    fi
}

# Start a miner
start_miner() {
    local suite=$1
    local miner_name=$2

    log_info "Starting $miner_name..."

    cd "$TEST_NODES_DIR/$suite/miner"

    # Check if already running
    if pgrep -f "xdagj-miner.jar" > /dev/null 2>&1; then
        log_warn "$miner_name might already be running"
    fi

    nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
    local pid=$!
    echo $pid > miner.pid
    sleep 1

    if ps -p $pid > /dev/null 2>&1; then
        log_success "$miner_name started (PID: $pid)"
    else
        log_error "Failed to start $miner_name"
        return 1
    fi
}

main() {
    echo "========================================="
    echo "   XDAG Test Environment - Start All"
    echo "========================================="
    echo ""

    # Check if JAR files exist
    if [ ! -f "$TEST_NODES_DIR/suite1/node/xdagj.jar" ]; then
        log_error "xdagj.jar not found! Please run update-nodes.sh first"
        exit 1
    fi

    if [ ! -f "$TEST_NODES_DIR/suite1/pool/xdagj-pool.jar" ]; then
        log_error "xdagj-pool.jar not found! Please build xdagj-pool project"
        exit 1
    fi

    if [ ! -f "$TEST_NODES_DIR/suite1/miner/xdagj-miner.jar" ]; then
        log_error "xdagj-miner.jar not found! Please build xdagj-miner project"
        exit 1
    fi

    # Start Suite1
    echo ""
    log_info "=== Starting Suite 1 ==="
    start_node "suite1" "Node1" || exit 1
    sleep 3
    start_pool "suite1" "Pool1" 3333 || exit 1
    sleep 2
    start_miner "suite1" "Miner1" || exit 1

    # Start Suite2
    echo ""
    log_info "=== Starting Suite 2 ==="
    start_node "suite2" "Node2" || exit 1
    sleep 3
    start_pool "suite2" "Pool2" 3334 || exit 1
    sleep 2
    start_miner "suite2" "Miner2" || exit 1

    echo ""
    echo "========================================="
    log_success "All services started successfully!"
    echo "========================================="
    echo ""
    echo "Next steps:"
    echo "  1. Check status:  ./scripts/status.sh"
    echo "  2. View logs:     ./scripts/watch-logs.sh"
    echo "  3. Compare nodes: ./scripts/compare-nodes.sh"
    echo ""
}

main "$@"
