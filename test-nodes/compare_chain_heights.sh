#!/bin/bash

# Script to compare main block hashes at each height between two nodes
# Usage: ./compare_chain_heights.sh

NODE1_URL="http://localhost:10001"
NODE2_URL="http://localhost:10002"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "XDAG Chain Height Comparison"
echo "=========================================="
echo ""

# Get chain lengths
echo "Fetching chain information..."
NODE1_LENGTH=$(curl -s "$NODE1_URL/api/v1/node/status" | python3 -c "import sys, json; print(json.load(sys.stdin)['mainChainLength'])" 2>/dev/null)
NODE2_LENGTH=$(curl -s "$NODE2_URL/api/v1/node/status" | python3 -c "import sys, json; print(json.load(sys.stdin)['mainChainLength'])" 2>/dev/null)

if [ -z "$NODE1_LENGTH" ] || [ -z "$NODE2_LENGTH" ]; then
    echo "Error: Failed to fetch chain lengths from one or both nodes"
    exit 1
fi

echo "Node1 chain length: $NODE1_LENGTH"
echo "Node2 chain length: $NODE2_LENGTH"
echo ""

# Determine max height to compare
MAX_HEIGHT=$NODE1_LENGTH
if [ "$NODE2_LENGTH" -gt "$NODE1_LENGTH" ]; then
    MAX_HEIGHT=$NODE2_LENGTH
fi

echo "Comparing blocks from height 1 to $MAX_HEIGHT..."
echo ""
echo "--------------------------------------------------------------------------------------------------------"
printf "%-8s | %-66s | %-66s | %s\n" "Height" "Node1 Block Hash" "Node2 Block Hash" "Match"
echo "--------------------------------------------------------------------------------------------------------"

# Compare each height
MATCH_COUNT=0
MISMATCH_COUNT=0

for ((height=1; height<=MAX_HEIGHT; height++)); do
    # Convert height to hex
    HEX_HEIGHT=$(printf "0x%x" $height)

    # Get block from Node1
    NODE1_HASH=$(curl -s "$NODE1_URL/api/v1/blocks/$HEX_HEIGHT" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('hash', 'NOT_FOUND'))" 2>/dev/null)

    # Get block from Node2
    NODE2_HASH=$(curl -s "$NODE2_URL/api/v1/blocks/$HEX_HEIGHT" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('hash', 'NOT_FOUND'))" 2>/dev/null)

    # Check if hashes match
    if [ "$NODE1_HASH" == "$NODE2_HASH" ] && [ "$NODE1_HASH" != "NOT_FOUND" ]; then
        MATCH="${GREEN}✓${NC}"
        MATCH_COUNT=$((MATCH_COUNT + 1))
    else
        MATCH="${RED}✗${NC}"
        MISMATCH_COUNT=$((MISMATCH_COUNT + 1))
    fi

    # Print comparison (show full hash)
    if [ "$NODE1_HASH" == "$NODE2_HASH" ]; then
        printf "%-8s | %-66s | %-66s | %b\n" "$height" "$NODE1_HASH" "$NODE2_HASH" "$MATCH"
    else
        printf "%-8s | ${YELLOW}%-66s${NC} | ${YELLOW}%-66s${NC} | %b\n" "$height" "$NODE1_HASH" "$NODE2_HASH" "$MATCH"
    fi
done

echo "--------------------------------------------------------------------------------------------------------"
echo ""
echo "Summary:"
echo "  Total heights compared: $MAX_HEIGHT"
echo -e "  ${GREEN}Matching blocks: $MATCH_COUNT${NC}"
echo -e "  ${RED}Mismatching blocks: $MISMATCH_COUNT${NC}"

if [ "$MISMATCH_COUNT" -eq 0 ]; then
    echo -e "\n${GREEN}✓ All blocks match - chains are in sync!${NC}"
else
    echo -e "\n${RED}✗ Chains have diverged - reorganization may be needed${NC}"
fi
echo ""
