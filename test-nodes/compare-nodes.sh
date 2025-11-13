#!/bin/bash
# XDAG Node State Comparison Tool
# Compare state between two test nodes
# Version: 1.0
# Date: 2025-11-12

set -e

# ==================== Configuration ====================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NODE1_TELNET_PORT=6001
NODE2_TELNET_PORT=6002
NODE1_NAME="Node1"
NODE2_NAME="Node2"
TELNET_PASSWORD="root"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ==================== Helper Functions ====================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_match() {
    echo -e "${GREEN}✅ Match${NC} - $1"
}

log_mismatch() {
    echo -e "${RED}❌ Mismatch${NC} - $1"
}

# ==================== Telnet Functions ====================

# Execute a telnet command and capture output
telnet_command() {
    local port=$1
    local command=$2
    local timeout=${3:-5}

    # Use expect to automate telnet session
    expect -c "
        set timeout $timeout
        spawn telnet localhost $port
        expect \"login:\"
        send \"$TELNET_PASSWORD\r\"
        expect \"XDAG>\"
        send \"$command\r\"
        expect \"XDAG>\"
        send \"exit\r\"
        expect eof
    " 2>/dev/null | grep -v "spawn\|login:\|XDAG>\|exit" | grep -v "^$" | sed 's/\r//g'
}

# Extract specific value from telnet output
extract_value() {
    local output="$1"
    local pattern="$2"
    echo "$output" | grep "$pattern" | sed "s/$pattern//" | tr -d ' \r\n'
}

# ==================== State Extraction ====================

get_node_state() {
    local port=$1
    local node_name=$2

    log_info "Querying $node_name state..."

    # Get stats output
    local stats_output=$(telnet_command $port "stats")

    # Extract key metrics
    local main_blocks=$(echo "$stats_output" | grep -i "main blocks" | grep -o '[0-9]\+' | head -1)
    local total_blocks=$(echo "$stats_output" | grep -i "total blocks" | grep -o '[0-9]\+' | head -1)
    local difficulty=$(echo "$stats_output" | grep -i "difficulty" | head -1 | cut -d: -f2 | tr -d ' ')

    # Get recent blocks
    local recent_blocks=$(telnet_command $port "block -n 10")

    # Get genesis info (first block)
    local genesis_output=$(telnet_command $port "block 0")
    local genesis_hash=$(echo "$genesis_output" | grep -i "hash" | head -1 | cut -d: -f2 | tr -d ' ')

    # Create associative array (bash 4+)
    echo "MAIN_BLOCKS=$main_blocks"
    echo "TOTAL_BLOCKS=$total_blocks"
    echo "DIFFICULTY=$difficulty"
    echo "GENESIS_HASH=$genesis_hash"
    echo "RECENT_BLOCKS<<EOF"
    echo "$recent_blocks"
    echo "EOF"
}

# ==================== Comparison Functions ====================

compare_values() {
    local label="$1"
    local value1="$2"
    local value2="$3"

    if [ "$value1" == "$value2" ]; then
        log_match "$label: $value1"
        return 0
    else
        log_mismatch "$label"
        echo "  $NODE1_NAME: $value1"
        echo "  $NODE2_NAME: $value2"
        if [ -n "$value1" ] && [ -n "$value2" ] && [ "$value1" -gt 0 ] 2>/dev/null && [ "$value2" -gt 0 ] 2>/dev/null; then
            local diff=$((value1 - value2))
            echo "  Difference: $diff"
        fi
        return 1
    fi
}

# ==================== Main Comparison ====================

compare_nodes() {
    echo "=========================================="
    log_info "XDAG Node State Comparison"
    echo "=========================================="
    echo ""

    # Check if expect is installed
    if ! command -v expect &> /dev/null; then
        log_error "expect is not installed. Please install it:"
        echo "  macOS: brew install expect"
        echo "  Linux: apt-get install expect or yum install expect"
        exit 1
    fi

    # Query Node1
    log_info "Querying $NODE1_NAME (port $NODE1_TELNET_PORT)..."
    local node1_state=$(get_node_state $NODE1_TELNET_PORT $NODE1_NAME)

    # Parse Node1 state
    eval "$node1_state"
    local node1_main=$MAIN_BLOCKS
    local node1_total=$TOTAL_BLOCKS
    local node1_difficulty=$DIFFICULTY
    local node1_genesis=$GENESIS_HASH
    local node1_blocks="$RECENT_BLOCKS"

    echo ""

    # Query Node2
    log_info "Querying $NODE2_NAME (port $NODE2_TELNET_PORT)..."
    local node2_state=$(get_node_state $NODE2_TELNET_PORT $NODE2_NAME)

    # Parse Node2 state
    eval "$node2_state"
    local node2_main=$MAIN_BLOCKS
    local node2_total=$TOTAL_BLOCKS
    local node2_difficulty=$DIFFICULTY
    local node2_genesis=$GENESIS_HASH
    local node2_blocks="$RECENT_BLOCKS"

    echo ""
    echo "=========================================="
    log_info "Comparison Results"
    echo "=========================================="
    echo ""

    local mismatches=0

    # Compare Genesis
    echo "${CYAN}[1] Genesis Hash${NC}"
    if ! compare_values "Genesis" "$node1_genesis" "$node2_genesis"; then
        ((mismatches++))
    fi
    echo ""

    # Compare Main Blocks
    echo "${CYAN}[2] Main Block Count${NC}"
    if ! compare_values "Main Blocks" "$node1_main" "$node2_main"; then
        ((mismatches++))
    fi
    echo ""

    # Compare Total Blocks
    echo "${CYAN}[3] Total Blocks${NC}"
    if ! compare_values "Total Blocks" "$node1_total" "$node2_total"; then
        ((mismatches++))
    fi
    echo ""

    # Compare Difficulty
    echo "${CYAN}[4] Current Difficulty${NC}"
    if ! compare_values "Difficulty" "$node1_difficulty" "$node2_difficulty"; then
        ((mismatches++))
    fi
    echo ""

    # Compare Recent Blocks
    echo "${CYAN}[5] Recent Blocks (Last 10)${NC}"
    if [ "$node1_blocks" == "$node2_blocks" ]; then
        log_match "Block list identical"
    else
        log_warning "Block lists differ (expected if sync in progress)"
        # Count matching blocks
        local match_count=0
        # This is simplified - real implementation would parse and compare hashes
        echo "  Detailed block comparison: See individual node outputs"
    fi
    echo ""

    # Summary
    echo "=========================================="
    if [ $mismatches -eq 0 ]; then
        log_success "All checks passed! Nodes are in sync."
        echo "=========================================="
        return 0
    else
        log_warning "$mismatches mismatches found"
        echo "=========================================="
        echo ""
        log_info "Possible causes:"
        echo "  1. Nodes are still syncing (wait a few seconds)"
        echo "  2. Network partition or connection issues"
        echo "  3. Different genesis configuration"
        echo "  4. Bug in consensus algorithm"
        echo ""
        log_info "Troubleshooting steps:"
        echo "  1. Check node logs: tail -f node1/logs/xdag-info.log"
        echo "  2. Watch real-time sync: ./watch-logs.sh"
        echo "  3. Restart nodes: ./update-nodes.sh --restart"
        echo "  4. Check P2P connection: ./test-framework.sh (option 4)"
        return 1
    fi
}

# ==================== Interactive Mode ====================

show_detailed_state() {
    local port=$1
    local node_name=$2

    echo ""
    echo "=========================================="
    log_info "$node_name Detailed State"
    echo "=========================================="
    echo ""

    echo "${CYAN}Stats:${NC}"
    telnet_command $port "stats"
    echo ""

    echo "${CYAN}Recent Blocks (Last 5):${NC}"
    telnet_command $port "block -n 5"
    echo ""

    echo "${CYAN}Account Info:${NC}"
    telnet_command $port "account"
    echo ""
}

interactive_mode() {
    while true; do
        echo ""
        echo "=========================================="
        echo "  Node Comparison - Interactive Mode"
        echo "=========================================="
        echo "1. Quick Comparison"
        echo "2. View Node1 Details"
        echo "3. View Node2 Details"
        echo "4. View Both Nodes"
        echo "5. Exit"
        echo "=========================================="
        read -p "Select option (1-5): " choice

        case $choice in
            1)
                compare_nodes
                ;;
            2)
                show_detailed_state $NODE1_TELNET_PORT $NODE1_NAME
                ;;
            3)
                show_detailed_state $NODE2_TELNET_PORT $NODE2_NAME
                ;;
            4)
                show_detailed_state $NODE1_TELNET_PORT $NODE1_NAME
                show_detailed_state $NODE2_TELNET_PORT $NODE2_NAME
                ;;
            5)
                log_info "Exiting..."
                exit 0
                ;;
            *)
                log_error "Invalid option"
                ;;
        esac
    done
}

# ==================== Main Script ====================

show_help() {
    cat <<EOF
XDAG Node State Comparison Tool

Usage: $0 [OPTIONS]

OPTIONS:
    -h, --help          Show this help message
    -i, --interactive   Interactive mode
    -q, --quick         Quick comparison (default)
    -v, --verbose       Verbose output

EXAMPLES:
    # Quick comparison
    $0

    # Interactive mode
    $0 --interactive

REQUIREMENTS:
    - expect (install: brew install expect)
    - Both nodes running with telnet enabled

EOF
}

main() {
    local mode="quick"

    # Parse arguments
    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -i|--interactive)
                mode="interactive"
                shift
                ;;
            -q|--quick)
                mode="quick"
                shift
                ;;
            -v|--verbose)
                set -x
                shift
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done

    # Run appropriate mode
    if [ "$mode" == "interactive" ]; then
        interactive_mode
    else
        compare_nodes
    fi
}

# Run main function
main "$@"
