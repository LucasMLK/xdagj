#!/bin/bash

# XDAG Test Environment - Status Check
# Shows running status of all components

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NODES_DIR="$(dirname "$SCRIPT_DIR")"
NODE_PID_FILE="xdag.pid"
NODE1_HTTP_PORT=10001
NODE2_HTTP_PORT=10002
HTTP_TIMEOUT=3

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Check if a process is running
is_running() {
    local pid=$1
    if ps -p $pid > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Check if port is in use
check_port() {
    local port=$1
    if lsof -i :$port > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Get process status
get_process_status() {
    local pid_file=$1
    local component_name=$2

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if is_running $pid; then
            echo -e "${GREEN}✓ Running${NC} (PID: $pid)"
            return 0
        else
            echo -e "${RED}✗ Stopped${NC} (stale PID file)"
            return 1
        fi
    else
        echo -e "${RED}✗ Stopped${NC}"
        return 1
    fi
}

# Get port status
get_port_status() {
    local port=$1

    if check_port $port; then
        echo -e "${GREEN}✓ Listening${NC}"
        return 0
    else
        echo -e "${RED}✗ Not listening${NC}"
        return 1
    fi
}

# Print section header
section() {
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}$1${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Print component status
component_status() {
    local name=$1
    local status=$2
    printf "  %-15s %b\n" "$name:" "$status"
}

# Fetch node status via HTTP API
fetch_node_status() {
    local port=$1
    curl -s --max-time "$HTTP_TIMEOUT" "http://127.0.0.1:${port}/api/v1/node/status"
}

# Print HTTP API metrics (sync state, heights, latest hash, mining)
print_http_metrics() {
    local name=$1
    local port=$2
    local response
    response=$(fetch_node_status "$port")

    if [ -z "$response" ]; then
        component_status "HTTP API" "${RED}✗ Unreachable${NC}"
        return 1
    fi

    local parsed
    parsed=$(python3 - "$response" <<'PY'
import json, sys
try:
    data = json.loads(sys.argv[1])
except Exception:
    sys.exit(1)
sync_state = data.get("syncState", "unknown")
latest_height = data.get("latestBlockHeight", data.get("mainChainLength", "n/a"))
main_chain = data.get("mainChainLength", "n/a")
latest_hash = data.get("latestBlockHash", "n/a")
mining_status = data.get("miningStatus", data.get("message", "n/a"))
print(f"{sync_state}|{latest_height}|{main_chain}|{latest_hash}|{mining_status}")
PY
)

    if [ -z "$parsed" ]; then
        component_status "HTTP API" "${RED}✗ Invalid response${NC}"
        return 1
    fi

    IFS='|' read -r sync_state latest_height main_chain latest_hash mining_status <<< "$parsed"

    local short_hash="-"
    if [ -n "$latest_hash" ] && [ "$latest_hash" != "n/a" ]; then
        short_hash="${latest_hash:0:18}..."
    fi

    component_status "HTTP API" "${GREEN}✓ Responding${NC}"
    component_status "Sync State" "$sync_state"
    component_status "Main Height" "$main_chain"
    component_status "Latest Hash" "$short_hash"
    component_status "Mining" "$mining_status"
    return 0
}

main() {
    echo "========================================="
    echo "   XDAG Test Environment - Status"
    echo "========================================="

    # Suite 1 Status
    section "Suite 1 Status"

    echo ""
    echo -e "${BLUE}Node1:${NC}"
    component_status "Process" "$(get_process_status "$TEST_NODES_DIR/suite1/node/$NODE_PID_FILE" "Node1")"
    component_status "P2P (8001)" "$(get_port_status 8001)"
    component_status "HTTP (10001)" "$(get_port_status 10001)"
    print_http_metrics "Node1" "$NODE1_HTTP_PORT"

    echo ""
    echo -e "${BLUE}Pool1:${NC}"
    component_status "Process" "$(get_process_status "$TEST_NODES_DIR/suite1/pool/pool.pid" "Pool1")"
    component_status "Stratum (3333)" "$(get_port_status 3333)"

    echo ""
    echo -e "${BLUE}Miner1:${NC}"
    component_status "Process" "$(get_process_status "$TEST_NODES_DIR/suite1/miner/miner.pid" "Miner1")"

    # Suite 2 Status
    section "Suite 2 Status"

    echo ""
    echo -e "${BLUE}Node2:${NC}"
    component_status "Process" "$(get_process_status "$TEST_NODES_DIR/suite2/node/$NODE_PID_FILE" "Node2")"
    component_status "P2P (8002)" "$(get_port_status 8002)"
    component_status "HTTP (10002)" "$(get_port_status 10002)"
    print_http_metrics "Node2" "$NODE2_HTTP_PORT"

    echo ""
    echo -e "${BLUE}Pool2:${NC}"
    component_status "Process" "$(get_process_status "$TEST_NODES_DIR/suite2/pool/pool.pid" "Pool2")"
    component_status "Stratum (3334)" "$(get_port_status 3334)"

    echo ""
    echo -e "${BLUE}Miner2:${NC}"
    component_status "Process" "$(get_process_status "$TEST_NODES_DIR/suite2/miner/miner.pid" "Miner2")"

    # Recent Logs Summary
    section "Recent Activity"

    echo ""
    echo -e "${BLUE}Node1 (last 3 lines):${NC}"
    if [ -f "$TEST_NODES_DIR/suite1/node/logs/xdag-info.log" ]; then
        tail -3 "$TEST_NODES_DIR/suite1/node/logs/xdag-info.log" 2>/dev/null | sed 's/^/  /'
    else
        echo "  No log file found"
    fi

    echo ""
    echo -e "${BLUE}Node2 (last 3 lines):${NC}"
    if [ -f "$TEST_NODES_DIR/suite2/node/logs/xdag-info.log" ]; then
        tail -3 "$TEST_NODES_DIR/suite2/node/logs/xdag-info.log" 2>/dev/null | sed 's/^/  /'
    else
        echo "  No log file found"
    fi

    echo ""
    echo -e "${BLUE}Pool1 Connection Status:${NC}"
    if [ -f "$TEST_NODES_DIR/suite1/pool/pool.log" ]; then
        grep -i "connected\|worker" "$TEST_NODES_DIR/suite1/pool/pool.log" 2>/dev/null | tail -2 | sed 's/^/  /' || echo "  No connection info"
    else
        echo "  No log file found"
    fi

    echo ""
    echo -e "${BLUE}Pool2 Connection Status:${NC}"
    if [ -f "$TEST_NODES_DIR/suite2/pool/pool.log" ]; then
        grep -i "connected\|worker" "$TEST_NODES_DIR/suite2/pool/pool.log" 2>/dev/null | tail -2 | sed 's/^/  /' || echo "  No connection info"
    else
        echo "  No log file found"
    fi

    # Summary
    section "Summary"

    echo ""
    local total_running=0
    local total_services=6

    [ -f "$TEST_NODES_DIR/suite1/node/$NODE_PID_FILE" ] && is_running $(cat "$TEST_NODES_DIR/suite1/node/$NODE_PID_FILE") && total_running=$((total_running + 1))
    [ -f "$TEST_NODES_DIR/suite1/pool/pool.pid" ] && is_running $(cat "$TEST_NODES_DIR/suite1/pool/pool.pid") && total_running=$((total_running + 1))
    [ -f "$TEST_NODES_DIR/suite1/miner/miner.pid" ] && is_running $(cat "$TEST_NODES_DIR/suite1/miner/miner.pid") && total_running=$((total_running + 1))
    [ -f "$TEST_NODES_DIR/suite2/node/$NODE_PID_FILE" ] && is_running $(cat "$TEST_NODES_DIR/suite2/node/$NODE_PID_FILE") && total_running=$((total_running + 1))
    [ -f "$TEST_NODES_DIR/suite2/pool/pool.pid" ] && is_running $(cat "$TEST_NODES_DIR/suite2/pool/pool.pid") && total_running=$((total_running + 1))
    [ -f "$TEST_NODES_DIR/suite2/miner/miner.pid" ] && is_running $(cat "$TEST_NODES_DIR/suite2/miner/miner.pid") && total_running=$((total_running + 1))

    echo -e "  Services running: ${GREEN}$total_running${NC} / $total_services"

    if [ $total_running -eq $total_services ]; then
        echo -e "  Status: ${GREEN}✓ All services operational${NC}"
    elif [ $total_running -eq 0 ]; then
        echo -e "  Status: ${RED}✗ All services stopped${NC}"
    else
        echo -e "  Status: ${YELLOW}⚠ Some services not running${NC}"
    fi

    echo ""
    echo "========================================="
    echo ""
    echo "Commands:"
    echo "  Start all:   ./scripts/start-all.sh"
    echo "  Stop all:    ./scripts/stop-all.sh"
    echo "  View logs:   ./scripts/watch-logs.sh"
    echo "  Compare:     ./scripts/compare-nodes.sh"
    echo ""
}

main "$@"
