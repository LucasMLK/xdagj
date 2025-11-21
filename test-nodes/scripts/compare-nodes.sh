#!/bin/bash
# XDAG Node State Comparison Tool (HTTP-based)
# Compares two local test nodes via the REST API

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NODE1_HTTP_PORT=10001
NODE2_HTTP_PORT=10002
HTTP_TIMEOUT=4

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

log_match() { echo -e "${GREEN}✅ Match${NC} - $1"; }
log_mismatch() { echo -e "${RED}❌ Mismatch${NC} - $1"; }

fetch_json() {
    local port=$1
    local path=$2
    curl -s --max-time "$HTTP_TIMEOUT" "http://127.0.0.1:${port}${path}"
}

get_total_pages() {
    local json="$1"
    python3 - "$json" <<'PY' 2>/dev/null || echo 1
import json, sys
try:
    data = json.loads(sys.argv[1])
except Exception:
    print(1)
    sys.exit(0)
pagination = data.get("pagination") or {}
print(pagination.get("totalPages", 1) or 1)
PY
}

get_node_snapshot() {
    local name=$1
    local port=$2

    local status_json recent_json tip_json genesis_json total_pages

    status_json=$(fetch_json "$port" "/api/v1/node/status")
    if [ -z "$status_json" ]; then
        log_error "$name HTTP status endpoint did not respond"
        return 1
    fi

    recent_json=$(fetch_json "$port" "/api/v1/blocks?page=1&size=10")
    if [ -z "$recent_json" ]; then
        log_error "$name blocks endpoint did not respond"
        return 1
    fi

    tip_json=$(fetch_json "$port" "/api/v1/blocks?page=1&size=1")
    if [ -z "$tip_json" ]; then
        log_error "$name tip block endpoint did not respond"
        return 1
    fi

    total_pages=$(get_total_pages "$tip_json")
    genesis_json=$(fetch_json "$port" "/api/v1/blocks?page=${total_pages}&size=1")
    if [ -z "$genesis_json" ]; then
        log_warning "$name genesis block request failed (page ${total_pages})"
        genesis_json='{"data":[]}'
    fi

    python3 - "$status_json" "$recent_json" "$tip_json" "$genesis_json" <<'PY'
import json, sys

status = json.loads(sys.argv[1])
recent = json.loads(sys.argv[2])
tip = json.loads(sys.argv[3])
genesis = json.loads(sys.argv[4])

main_chain = status.get("mainChainLength", 0)
latest_height = status.get("latestBlockHeight", main_chain)
latest_hash = status.get("latestBlockHash", "n/a")
sync_state = status.get("syncState", "unknown")
mining_status = status.get("miningStatus", status.get("message", "n/a"))

tip_blocks = tip.get("data") or []
difficulty = tip_blocks[0].get("difficulty", "n/a") if tip_blocks else "n/a"

genesis_blocks = genesis.get("data") or []
genesis_hash = genesis_blocks[0].get("hash", "n/a") if genesis_blocks else "n/a"

recent_lines = []
for block in (recent.get("data") or []):
    height = block.get("height", "?")
    block_hash = block.get("hash", "?")
    recent_lines.append(f"{height}:{block_hash}")

print(f"MAIN_BLOCKS={main_chain}")
print(f"TOTAL_BLOCKS={latest_height}")
print(f"DIFFICULTY={difficulty}")
print(f"GENESIS_HASH={genesis_hash}")
print(f"LATEST_HASH={latest_hash}")
print(f"SYNC_STATE={sync_state}")
print(f"MINING_STATUS={mining_status}")
print(f"RECENT_BLOCKS={','.join(recent_lines)}")
PY
}

compare_values() {
    local label="$1"
    local value1="$2"
    local value2="$3"

    if [ "$value1" == "$value2" ]; then
        log_match "$label: $value1"
        return 0
    else
        log_mismatch "$label"
        echo "  Node1: $value1"
        echo "  Node2: $value2"
        return 1
    fi
}

compare_lists() {
    local label="$1"
    local csv1="$2"
    local csv2="$3"

    if [ "$csv1" == "$csv2" ]; then
        log_match "$label"
        return 0
    fi

    log_warning "$label differ"
    IFS=',' read -ra arr1 <<< "$csv1"
    IFS=',' read -ra arr2 <<< "$csv2"

    local matches=0
    for item in "${arr1[@]}"; do
        for other in "${arr2[@]}"; do
            if [ "$item" == "$other" ] && [ -n "$item" ]; then
                ((matches++))
                break
            fi
        done
    done
    echo "  Shared blocks: $matches"
    return 1
}

compare_nodes() {
    echo "=========================================="
    log_info "XDAG Node State Comparison (HTTP API)"
    echo "=========================================="
    echo ""

    # Collect snapshots
    local node1_state node2_state
    if ! node1_state=$(get_node_snapshot "Node1" "$NODE1_HTTP_PORT"); then
        log_error "Failed to read Node1 state"
        exit 1
    fi

    if ! node2_state=$(get_node_snapshot "Node2" "$NODE2_HTTP_PORT"); then
        log_error "Failed to read Node2 state"
        exit 1
    fi

    eval "$node1_state"
    local node1_main=$MAIN_BLOCKS
    local node1_total=$TOTAL_BLOCKS
    local node1_diff=$DIFFICULTY
    local node1_genesis=$GENESIS_HASH
    local node1_hash=$LATEST_HASH
    local node1_sync=$SYNC_STATE
    local node1_mining=$MINING_STATUS
    local node1_recent=$RECENT_BLOCKS

    eval "$node2_state"
    local node2_main=$MAIN_BLOCKS
    local node2_total=$TOTAL_BLOCKS
    local node2_diff=$DIFFICULTY
    local node2_genesis=$GENESIS_HASH
    local node2_hash=$LATEST_HASH
    local node2_sync=$SYNC_STATE
    local node2_mining=$MINING_STATUS
    local node2_recent=$RECENT_BLOCKS

    local mismatches=0

    echo -e "${CYAN}[1] Genesis Hash${NC}"
    compare_values "Genesis" "$node1_genesis" "$node2_genesis" || ((mismatches++))
    echo ""

    echo -e "${CYAN}[2] Main Chain Length${NC}"
    compare_values "Main Blocks" "$node1_main" "$node2_main" || ((mismatches++))
    echo ""

    echo -e "${CYAN}[3] Latest Block Height${NC}"
    compare_values "Block Height" "$node1_total" "$node2_total" || ((mismatches++))
    echo ""

    echo -e "${CYAN}[4] Latest Block Hash${NC}"
    compare_values "Latest Hash" "$node1_hash" "$node2_hash" || ((mismatches++))
    echo ""

    echo -e "${CYAN}[5] Difficulty${NC}"
    compare_values "Difficulty" "$node1_diff" "$node2_diff" || ((mismatches++))
    echo ""

    echo -e "${CYAN}[6] Sync / Mining State${NC}"
    compare_values "Sync State" "$node1_sync" "$node2_sync" || ((mismatches++))
    compare_values "Mining Status" "$node1_mining" "$node2_mining" || ((mismatches++))
    echo ""

    echo -e "${CYAN}[7] Recent Blocks (last 10)${NC}"
    compare_lists "Recent block list" "$node1_recent" "$node2_recent" || ((mismatches++))
    echo ""

    echo "=========================================="
    if [ $mismatches -eq 0 ]; then
        log_success "All checks passed. Nodes are consistent."
    else
        log_warning "$mismatches mismatches detected"
        echo "  - Nodes may still be syncing, or blocks diverged."
        echo "  - Check node logs under suite*/node/logs for details."
    fi
    echo "=========================================="
}

compare_nodes "$@"
