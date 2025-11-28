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
# Only returns the first match to avoid confusion with nested fields
extract_json_field() {
    local json="$1"
    local field="$2"
    echo "$json" | grep -o "\"$field\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

# Print table separator
print_separator() {
    echo "+--------+----------+---------------------------+---------------------------+--------+--------+"
}

# Print table header
print_header() {
    print_separator
    printf "| %-6s | %-8s | %-25s | %-25s | %-6s | %-6s |\n" \
        "Height" "Epoch" "Node1 Hash" "Node2 Hash" "Diff" "Match"
    print_separator
}

echo ""
echo "╔══════════════════════════════════════════════════════════════════════════════╗"
echo "║                  XDAG Node Block Comparison Tool                             ║"
echo "╚══════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Get current block number from both nodes
echo "🔍 Querying nodes..."
node1_block_hex=$(curl -s "$NODE1_API/blocks/number" 2>/dev/null | grep -o '"blockNumber":"[^"]*"' | cut -d'"' -f4)
node2_block_hex=$(curl -s "$NODE2_API/blocks/number" 2>/dev/null | grep -o '"blockNumber":"[^"]*"' | cut -d'"' -f4)

if [ -z "$node1_block_hex" ] || [ -z "$node2_block_hex" ]; then
    echo -e "${RED}ERROR: Failed to query one or both nodes${NC}"
    exit 1
fi

node1_height=$(hex_to_dec "$node1_block_hex")
node2_height=$(hex_to_dec "$node2_block_hex")

echo ""
echo "📊 Node Status:"
echo "┌────────────────────────────────────────┐"
printf "│ %-20s: %8s blocks │\n" "$NODE1_NAME (Port 10001)" "$node1_height"
printf "│ %-20s: %8s blocks │\n" "$NODE2_NAME (Port 10002)" "$node2_height"
echo "└────────────────────────────────────────┘"
echo ""

# Check if heights match
if [ "$node1_height" -ne "$node2_height" ]; then
    echo -e "${YELLOW}⚠ WARNING: Block heights differ!${NC}"
fi

# Determine comparison range
max_height=$(( node1_height < node2_height ? node1_height : node2_height ))

# Statistics
total_compared=0
identical_blocks=0
different_blocks=0
missing_blocks=0

echo "🔄 Comparing blocks (1 to $max_height)..."
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

    # Extract key fields from Node2
    node2_hash=$(extract_json_field "$node2_block" "hash")
    node2_epoch=$(extract_json_field "$node2_block" "epoch")
    node2_difficulty=$(extract_json_field "$node2_block" "difficulty")
    node2_state=$(extract_json_field "$node2_block" "state")

    total_compared=$((total_compared + 1))

    # Check if blocks exist
    if [ -z "$node1_hash" ] || [ -z "$node2_hash" ]; then
        missing_blocks=$((missing_blocks + 1))
        node1_display="${RED}MISSING${NC}"
        node2_display="${RED}MISSING${NC}"
        [ -n "$node1_hash" ] && node1_display="${node1_hash:0:20}"
        [ -n "$node2_hash" ] && node2_display="${node2_hash:0:20}"

        printf "| %-6s | %-8s | %-25s | %-25s | %-6s | ${RED}%-6s${NC} |\n" \
            "$height" "N/A" "$node1_display" "$node2_display" "N/A" "✗"
        continue
    fi

    # Prepare display values
    display_epoch=$(hex_to_dec "$node1_epoch")
    # Show first 14 chars (0x + 12 hex) ... last 8 chars for better visibility
    node1_hash_short="${node1_hash:0:14}...${node1_hash: -8}"
    node2_hash_short="${node2_hash:0:14}...${node2_hash: -8}"
    display_diff=$(hex_to_dec "$node1_difficulty")

    # Compare block data
    if [ "$node1_hash" = "$node2_hash" ] && \
       [ "$node1_epoch" = "$node2_epoch" ] && \
       [ "$node1_difficulty" = "$node2_difficulty" ] && \
       [ "$node1_state" = "$node2_state" ]; then
        # Identical blocks
        printf "| %-6s | %-8s | %-25s | %-25s | %-6s | ${GREEN}%-6s${NC} |\n" \
            "$height" "$display_epoch" "$node1_hash_short" "$node2_hash_short" "$display_diff" "✓"
        identical_blocks=$((identical_blocks + 1))
    else
        # Different blocks - show with red color
        different_blocks=$((different_blocks + 1))

        # Highlight differences
        if [ "$node1_hash" != "$node2_hash" ]; then
            node1_hash_short="${RED}${node1_hash:0:20}${NC}"
            node2_hash_short="${RED}${node2_hash:0:20}${NC}"
        fi

        # Show epoch mismatch
        if [ "$node1_epoch" != "$node2_epoch" ]; then
            node2_epoch_dec=$(hex_to_dec "$node2_epoch")
            display_epoch="${display_epoch}/${RED}${node2_epoch_dec}${NC}"
        fi

        # Show diff mismatch
        if [ "$node1_difficulty" != "$node2_difficulty" ]; then
            node2_diff=$(hex_to_dec "$node2_difficulty")
            display_diff="${display_diff}/${RED}${node2_diff}${NC}"
        fi

        printf "| %-6s | %-8s | %-25s | %-25s | %-6s | ${RED}%-6s${NC} |\n" \
            "$height" "$display_epoch" "$node1_hash_short" "$node2_hash_short" "$display_diff" "✗"

        # Show detailed diff below table row
        print_separator
        echo "  ${RED}▼ MISMATCH DETAILS for Height $height:${NC}"
        if [ "$node1_hash" != "$node2_hash" ]; then
            echo "    ${YELLOW}Hash:${NC}"
            echo "      Node1: $node1_hash"
            echo "      Node2: $node2_hash"
        fi
        if [ "$node1_epoch" != "$node2_epoch" ]; then
            echo "    ${YELLOW}Epoch:${NC} Node1=$(hex_to_dec $node1_epoch) vs Node2=$(hex_to_dec $node2_epoch)"
        fi
        if [ "$node1_difficulty" != "$node2_difficulty" ]; then
            echo "    ${YELLOW}Difficulty:${NC} Node1=$(hex_to_dec $node1_difficulty) vs Node2=$(hex_to_dec $node2_difficulty)"
        fi
        if [ "$node1_state" != "$node2_state" ]; then
            echo "    ${YELLOW}State:${NC} Node1=$node1_state vs Node2=$node2_state"
        fi
    fi
done

# Close table
print_separator

# Final summary
echo ""
echo "╔══════════════════════════════════════════════════════════════════════════════╗"
echo "║                         Comparison Summary                                   ║"
echo "╠══════════════════════════════════════════════════════════════════════════════╣"
printf "║ Total blocks compared:    %-51s║\n" "$total_compared"
printf "║ ${GREEN}Identical blocks:         %-51s${NC}║\n" "$identical_blocks"
printf "║ ${RED}Different blocks:         %-51s${NC}║\n" "$different_blocks"
printf "║ ${YELLOW}Missing blocks:           %-51s${NC}║\n" "$missing_blocks"
echo "╚══════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Conclusion
if [ "$different_blocks" -eq 0 ] && [ "$missing_blocks" -eq 0 ]; then
    echo -e "${GREEN}✓ SUCCESS: All blocks are identical!${NC}"
    echo "  The two nodes are perfectly synchronized."
    echo ""
    exit 0
else
    echo -e "${RED}✗ FAILURE: Nodes are not synchronized!${NC}"
    if [ "$different_blocks" -gt 0 ]; then
        echo "  - $different_blocks block(s) have different data"
    fi
    if [ "$missing_blocks" -gt 0 ]; then
        echo "  - $missing_blocks block(s) are missing on one or both nodes"
    fi
    echo ""
    exit 1
fi
