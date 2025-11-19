#!/bin/bash

# XDAG Test Environment - Stop All Services
# Stops Suite1 and Suite2 (Node + Pool + Miner)

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

# Stop nodes
stop_nodes() {
    log_info "Stopping nodes..."

    local stopped=0
    local pid

    # Stop Node1
    local pid_file="$TEST_NODES_DIR/suite1/node/$NODE_PID_FILE"
    if [ -f "$pid_file" ]; then
        pid=$(cat "$pid_file")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid
            log_success "Node1 stopped (PID: $pid)"
            stopped=$((stopped + 1))
        fi
        rm -f "$pid_file"
    fi

    # Stop Node2
    pid_file="$TEST_NODES_DIR/suite2/node/$NODE_PID_FILE"
    if [ -f "$pid_file" ]; then
        pid=$(cat "$pid_file")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid
            log_success "Node2 stopped (PID: $pid)"
            stopped=$((stopped + 1))
        fi
        rm -f "$pid_file"
    fi

    # Fallback: kill by process name
    if pkill -f "xdagj.jar"; then
        log_success "Additional node processes stopped"
        stopped=$((stopped + 1))
    fi

    if [ $stopped -eq 0 ]; then
        log_warn "No running nodes found"
    fi
}

# Stop pools
stop_pools() {
    log_info "Stopping pools..."

    local stopped=0

    # Stop Pool1
    if [ -f "$TEST_NODES_DIR/suite1/pool/pool.pid" ]; then
        local pid=$(cat "$TEST_NODES_DIR/suite1/pool/pool.pid")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid
            log_success "Pool1 stopped (PID: $pid)"
            stopped=$((stopped + 1))
        fi
        rm -f "$TEST_NODES_DIR/suite1/pool/pool.pid"
    fi

    # Stop Pool2
    if [ -f "$TEST_NODES_DIR/suite2/pool/pool.pid" ]; then
        local pid=$(cat "$TEST_NODES_DIR/suite2/pool/pool.pid")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid
            log_success "Pool2 stopped (PID: $pid)"
            stopped=$((stopped + 1))
        fi
        rm -f "$TEST_NODES_DIR/suite2/pool/pool.pid"
    fi

    # Fallback: kill by process name
    if pkill -f "xdagj-pool"; then
        log_success "Additional pool processes stopped"
        stopped=$((stopped + 1))
    fi

    if [ $stopped -eq 0 ]; then
        log_warn "No running pools found"
    fi
}

# Stop miners
stop_miners() {
    log_info "Stopping miners..."

    local stopped=0

    # Stop Miner1
    if [ -f "$TEST_NODES_DIR/suite1/miner/miner.pid" ]; then
        local pid=$(cat "$TEST_NODES_DIR/suite1/miner/miner.pid")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid
            log_success "Miner1 stopped (PID: $pid)"
            stopped=$((stopped + 1))
        fi
        rm -f "$TEST_NODES_DIR/suite1/miner/miner.pid"
    fi

    # Stop Miner2
    if [ -f "$TEST_NODES_DIR/suite2/miner/miner.pid" ]; then
        local pid=$(cat "$TEST_NODES_DIR/suite2/miner/miner.pid")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid
            log_success "Miner2 stopped (PID: $pid)"
            stopped=$((stopped + 1))
        fi
        rm -f "$TEST_NODES_DIR/suite2/miner/miner.pid"
    fi

    # Fallback: kill by process name
    if pkill -f "xdagj-miner"; then
        log_success "Additional miner processes stopped"
        stopped=$((stopped + 1))
    fi

    if [ $stopped -eq 0 ]; then
        log_warn "No running miners found"
    fi
}

main() {
    echo "========================================="
    echo "   XDAG Test Environment - Stop All"
    echo "========================================="
    echo ""

    stop_miners
    echo ""

    stop_pools
    echo ""

    stop_nodes
    echo ""

    # Wait a bit for processes to terminate
    sleep 2

    # Check if any processes are still running
    local still_running=0

    if pgrep -f "xdagj.jar" > /dev/null 2>&1; then
        log_warn "Some node processes still running"
        still_running=1
    fi

    if pgrep -f "xdagj-pool" > /dev/null 2>&1; then
        log_warn "Some pool processes still running"
        still_running=1
    fi

    if pgrep -f "xdagj-miner" > /dev/null 2>&1; then
        log_warn "Some miner processes still running"
        still_running=1
    fi

    if [ $still_running -eq 0 ]; then
        echo "========================================="
        log_success "All services stopped successfully!"
        echo "========================================="
    else
        echo "========================================="
        log_warn "Some processes may still be running"
        echo "========================================="
        echo ""
        echo "To force kill:"
        echo "  pkill -9 -f xdagj.jar"
        echo "  pkill -9 -f xdagj-pool"
        echo "  pkill -9 -f xdagj-miner"
    fi

    echo ""
}

main "$@"
