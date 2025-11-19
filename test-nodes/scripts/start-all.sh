#!/bin/bash

# XDAG Test Environment - Start All Services
# Starts Suite1 and Suite2 (Node + Pool + Miner)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NODES_DIR="$(dirname "$SCRIPT_DIR")"
NODE_PID_FILE="xdag.pid"

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

# Ensure a required artifact exists
check_artifact() {
    local path="$1"
    local label="$2"
    if [ ! -f "$path" ]; then
        log_error "$label not found at $path"
        exit 1
    fi
}

# Start a node
start_node() {
    local suite=$1
    local node_name=$2
    local node_dir="$TEST_NODES_DIR/$suite/node"
    local pid_file="$node_dir/$NODE_PID_FILE"

    log_info "Starting $node_name..."

    cd "$node_dir"

    # Always restart to pick up the latest binaries/config and avoid stale state
    ./stop.sh >/dev/null 2>&1 || true
    ./start.sh
    sleep 2

    if [ -f "$pid_file" ] && ps -p "$(cat "$pid_file")" > /dev/null 2>&1; then
        log_success "$node_name started (PID: $(cat "$pid_file"))"
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

    rm -f pool.pid
    nohup java -jar xdagj-pool.jar --config pool-config.conf > pool.log 2>&1 &
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

    rm -f miner.pid
    nohup java -jar xdagj-miner.jar --config miner-config.conf > miner.log 2>&1 &
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
    check_artifact "$TEST_NODES_DIR/suite1/node/xdagj.jar" "Suite1 node jar"
    check_artifact "$TEST_NODES_DIR/suite2/node/xdagj.jar" "Suite2 node jar"
    check_artifact "$TEST_NODES_DIR/suite1/pool/xdagj-pool.jar" "Suite1 pool jar"
    check_artifact "$TEST_NODES_DIR/suite2/pool/xdagj-pool.jar" "Suite2 pool jar"
    check_artifact "$TEST_NODES_DIR/suite1/miner/xdagj-miner.jar" "Suite1 miner jar"
    check_artifact "$TEST_NODES_DIR/suite2/miner/xdagj-miner.jar" "Suite2 miner jar"

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
