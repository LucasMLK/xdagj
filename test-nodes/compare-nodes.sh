#!/bin/bash

# XDAG Node Block Comparison Script
# Compares main blocks between two XDAG nodes via HTTP API

# Node configurations
NODE1_API="http://127.0.0.1:10001/api/v1"
NODE2_API="http://127.0.0.1:10002/api/v1"
NODE1_NAME="Node1"
NODE2_NAME="Node2"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Parse hex to decimal
hex_to_dec() {
    echo $((16#${1#0x}))
}

# Extract JSON field using grep (simple approach, no jq required)
extract_json_field() {
    local json="$1"
    local field="$2"
    echo "$json" | grep -o "\"$field\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

# Print table separator
print_separator() {
    echo "+--------+----------+---------------------------+---------------------------+--------+----------------------+--------+"
}

# Print table header
print_header() {
    print_separator
    printf "| %-6s | %-8s | %-25s | %-25s | %-6s | %-20s | %-6s |\n" \
        "Height" "Epoch" "Node1 Hash" "Node2 Hash" "Diff" "Coinbase" "Match"
    print_separator
}

# Print epoch blocks for a node
print_epoch_blocks() {
    local api="$1"
    local epoch="$2"
    local node_name="$3"

    local resp=$(curl -s "$api/blocks/epoch/$epoch" 2>/dev/null)
    local count=$(echo "$resp" | grep -o '"blockCount":[0-9]*' | cut -d':' -f2)

    if [ "${count:-0}" -gt 0 ] 2>/dev/null; then
        echo "$resp" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    blocks = data.get('blocks', [])
    parts = []
    for b in blocks:
        h = b.get('hash', '')[:16] + '...'
        state = b.get('state', '')
        cb = b.get('coinbase', '')
        cb_short = cb[:6] + '..' + cb[-3:] if len(cb) > 9 else cb
        color = '\033[0;32m' if state == 'Main' else '\033[1;33m'
        nc = '\033[0m'
        parts.append(f'{color}{state}{nc}:{h}({cb_short})')
    print('  '.join(parts))
except:
    print('(error)')
" 2>/dev/null
    else
        echo "(no blocks)"
    fi
}

echo ""
echo "+====================================================================================================+"
echo "|                              XDAG Node Block Comparison Tool                                       |"
echo "+====================================================================================================+"
echo ""

# Get current block number from both nodes
echo "Querying nodes..."
node1_block_hex=$(curl -s "$NODE1_API/blocks/number" 2>/dev/null | grep -o '"blockNumber":"[^"]*"' | cut -d'"' -f4)
node2_block_hex=$(curl -s "$NODE2_API/blocks/number" 2>/dev/null | grep -o '"blockNumber":"[^"]*"' | cut -d'"' -f4)

if [ -z "$node1_block_hex" ] || [ -z "$node2_block_hex" ]; then
    echo -e "${RED}ERROR: Failed to query one or both nodes${NC}"
    exit 1
fi

node1_height=$(hex_to_dec "$node1_block_hex")
node2_height=$(hex_to_dec "$node2_block_hex")

echo ""
echo "Node Status:"
echo "+------------------------------------------+"
printf "| %-20s: %8s blocks   |\n" "$NODE1_NAME (Port 10001)" "$node1_height"
printf "| %-20s: %8s blocks   |\n" "$NODE2_NAME (Port 10002)" "$node2_height"
echo "+------------------------------------------+"
echo ""

# Check if heights match
if [ "$node1_height" -ne "$node2_height" ]; then
    echo -e "${YELLOW}WARNING: Block heights differ!${NC}"
fi

# Determine comparison range
max_height=$(( node1_height < node2_height ? node1_height : node2_height ))

# Statistics
total_compared=0
identical_blocks=0
different_blocks=0
missing_blocks=0
epoch_match_count=0
epoch_mismatch_count=0

echo "Comparing blocks (1 to $max_height)..."
echo ""

# Print table header
print_header

# Compare each block
for height in $(seq 1 $max_height); do
    # Query block from both nodes
    node1_block=$(curl -s "$NODE1_API/blocks/$height" 2>/dev/null)
    node2_block=$(curl -s "$NODE2_API/blocks/$height" 2>/dev/null)

    # Extract key fields from Node1
    node1_hash=$(extract_json_field "$node1_block" "hash")
    node1_epoch=$(extract_json_field "$node1_block" "epoch")
    node1_difficulty=$(extract_json_field "$node1_block" "difficulty")
    node1_state=$(extract_json_field "$node1_block" "state")
    node1_coinbase=$(extract_json_field "$node1_block" "coinbase")

    # Extract key fields from Node2
    node2_hash=$(extract_json_field "$node2_block" "hash")
    node2_epoch=$(extract_json_field "$node2_block" "epoch")
    node2_difficulty=$(extract_json_field "$node2_block" "difficulty")
    node2_state=$(extract_json_field "$node2_block" "state")
    node2_coinbase=$(extract_json_field "$node2_block" "coinbase")

    total_compared=$((total_compared + 1))

    # Check if blocks exist
    if [ -z "$node1_hash" ] || [ -z "$node2_hash" ]; then
        missing_blocks=$((missing_blocks + 1))
        printf "| %-6s | %-8s | %-25s | %-25s | %-6s | %-20s | ${RED}%-6s${NC} |\n" \
            "$height" "N/A" "MISSING" "MISSING" "N/A" "N/A" "X"
        continue
    fi

    # Prepare display values
    display_epoch=$(hex_to_dec "$node1_epoch")
    node1_hash_short="${node1_hash:0:14}...${node1_hash: -8}"
    node2_hash_short="${node2_hash:0:14}...${node2_hash: -8}"
    display_diff=$(hex_to_dec "$node1_difficulty")
    coinbase_short="${node1_coinbase:0:8}...${node1_coinbase: -4}"

    # Compare block data
    if [ "$node1_hash" = "$node2_hash" ] && \
       [ "$node1_epoch" = "$node2_epoch" ] && \
       [ "$node1_difficulty" = "$node2_difficulty" ] && \
       [ "$node1_state" = "$node2_state" ]; then
        printf "| %-6s | %-8s | %-25s | %-25s | %-6s | %-20s | ${GREEN}%-6s${NC} |\n" \
            "$height" "$display_epoch" "$node1_hash_short" "$node2_hash_short" "$display_diff" "$coinbase_short" "OK"
        identical_blocks=$((identical_blocks + 1))
    else
        different_blocks=$((different_blocks + 1))
        printf "| %-6s | %-8s | %-25s | %-25s | %-6s | %-20s | ${RED}%-6s${NC} |\n" \
            "$height" "$display_epoch" "$node1_hash_short" "$node2_hash_short" "$display_diff" "$coinbase_short" "X"
    fi

    # Get epoch block counts for comparison
    node1_epoch_resp=$(curl -s "$NODE1_API/blocks/epoch/$display_epoch" 2>/dev/null)
    node2_epoch_resp=$(curl -s "$NODE2_API/blocks/epoch/$display_epoch" 2>/dev/null)
    n1_count=$(echo "$node1_epoch_resp" | grep -o '"blockCount":[0-9]*' | cut -d':' -f2)
    n2_count=$(echo "$node2_epoch_resp" | grep -o '"blockCount":[0-9]*' | cut -d':' -f2)

    # Show epoch blocks inline
    if [ "${n1_count:-0}" -gt 1 ] || [ "${n2_count:-0}" -gt 1 ] || [ "$n1_count" != "$n2_count" ]; then
        # Multiple blocks or mismatch - show details
        if [ "$n1_count" = "$n2_count" ]; then
            epoch_match_count=$((epoch_match_count + 1))
            epoch_status="${GREEN}=${NC}"
        else
            epoch_mismatch_count=$((epoch_mismatch_count + 1))
            epoch_status="${RED}!${NC}"
        fi

        printf "|        |          | ${CYAN}Node1 epoch blocks (${n1_count:-0}):${NC} "
        print_epoch_blocks "$NODE1_API" "$display_epoch" "Node1"
        printf "|        |          | ${CYAN}Node2 epoch blocks (${n2_count:-0}):${NC} "
        print_epoch_blocks "$NODE2_API" "$display_epoch" "Node2"
    else
        epoch_match_count=$((epoch_match_count + 1))
    fi
done

# Close table
print_separator

# Summary
echo ""
echo "+==============================================================================+"
echo "|                           Comparison Summary                                 |"
echo "+==============================================================================+"
printf "| Total blocks compared:    %-51s|\n" "$total_compared"
printf "| ${GREEN}Identical blocks:         %-51s${NC}|\n" "$identical_blocks"
printf "| ${RED}Different blocks:         %-51s${NC}|\n" "$different_blocks"
printf "| ${YELLOW}Missing blocks:           %-51s${NC}|\n" "$missing_blocks"
echo "+------------------------------------------------------------------------------+"
printf "| ${GREEN}Matching epoch views:     %-51s${NC}|\n" "$epoch_match_count"
printf "| ${RED}Mismatched epoch views:   %-51s${NC}|\n" "$epoch_mismatch_count"
echo "+==============================================================================+"
echo ""

# Conclusion
if [ "$different_blocks" -eq 0 ] && [ "$missing_blocks" -eq 0 ] && [ "$epoch_mismatch_count" -eq 0 ]; then
    echo -e "${GREEN}SUCCESS: All blocks and epoch views are identical!${NC}"
    echo "  The two nodes are perfectly synchronized."
    echo ""
    exit 0
else
    echo -e "${RED}FAILURE: Nodes are not fully synchronized!${NC}"
    if [ "$different_blocks" -gt 0 ]; then
        echo "  - $different_blocks main chain block(s) differ"
    fi
    if [ "$missing_blocks" -gt 0 ]; then
        echo "  - $missing_blocks block(s) missing"
    fi
    if [ "$epoch_mismatch_count" -gt 0 ]; then
        echo "  - $epoch_mismatch_count epoch(s) have different block counts"
    fi
    echo ""
    exit 1
fi
