#!/bin/bash

# Test script for new epoch-based block query APIs
# Usage: ./test-epoch-api.sh [BASE_URL]

BASE_URL="${1:-http://localhost:10001}"

echo "=================================================="
echo "Testing Epoch-Based Block Query APIs"
echo "Base URL: $BASE_URL"
echo "=================================================="
echo

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to make API call and pretty-print JSON
test_api() {
    local name="$1"
    local url="$2"

    echo -e "${YELLOW}>>> Testing: $name${NC}"
    echo "URL: $url"
    echo

    response=$(curl -s "$url")

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}Response:${NC}"
        echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
    else
        echo "❌ Request failed"
    fi

    echo
    echo "--------------------------------------------------"
    echo
}

# Test 1: Get current block number first to determine valid epochs
echo -e "${YELLOW}>>> Getting current blockchain state...${NC}"
current_block=$(curl -s "$BASE_URL/api/v1/blocks/number" | python3 -c "import sys, json; data=json.load(sys.stdin); print(int(data['blockNumber'], 16))" 2>/dev/null)

if [ -n "$current_block" ] && [ "$current_block" -gt 0 ]; then
    echo -e "${GREEN}Current block height: $current_block${NC}"
    echo

    # Calculate recent epoch (assuming we have some blocks)
    # Use a small epoch number for testing (e.g., epoch 10)
    test_epoch=10

    if [ "$current_block" -gt 100 ]; then
        # For established chains, test a recent epoch
        test_epoch=$((current_block / 10))
    fi

    echo "Using test epoch: $test_epoch"
    echo
else
    echo "⚠️  Could not determine current block height, using default test epoch 10"
    test_epoch=10
    echo
fi

echo "=================================================="
echo "API Test Suite"
echo "=================================================="
echo

# Test 2: Get blocks by single epoch
test_api "Get blocks by epoch ($test_epoch)" \
    "$BASE_URL/api/v1/blocks/epoch/$test_epoch"

# Test 2a: Get blocks by epoch with pagination (page 1, size 10)
test_api "Get blocks by epoch with pagination ($test_epoch, page 1, size 10)" \
    "$BASE_URL/api/v1/blocks/epoch/$test_epoch?page=1&size=10"

# Test 2b: Get blocks by epoch with pagination (page 2, size 5)
test_api "Get blocks by epoch with pagination ($test_epoch, page 2, size 5)" \
    "$BASE_URL/api/v1/blocks/epoch/$test_epoch?page=2&size=5"

# Test 3: Get blocks by epoch range (small range)
from_epoch=$((test_epoch - 5))
to_epoch=$test_epoch

test_api "Get blocks by epoch range ($from_epoch to $to_epoch)" \
    "$BASE_URL/api/v1/blocks/epoch/range?fromEpoch=$from_epoch&toEpoch=$to_epoch"

# Test 4: Test with genesis epoch
test_api "Get blocks in genesis epoch (epoch 1)" \
    "$BASE_URL/api/v1/blocks/epoch/1"

# Test 5: Test with invalid epoch (should return empty)
test_api "Get blocks in non-existent epoch (999999)" \
    "$BASE_URL/api/v1/blocks/epoch/999999"

echo "=================================================="
echo "Test Summary"
echo "=================================================="
echo
echo "✓ All API endpoints tested"
echo
echo "Use cases for these APIs:"
echo "1. Consensus verification - check which blocks competed in each epoch"
echo "2. Fork analysis - identify orphan blocks and epoch winners"
echo "3. Mining statistics - analyze block distribution across epochs"
echo "4. Debug epoch competition - verify smallest hash wins logic"
echo "5. Pagination support - handle epochs with many blocks (network partitions)"
echo
echo "Example queries:"
echo "  # Get all blocks in epoch 12345"
echo "  curl $BASE_URL/api/v1/blocks/epoch/12345"
echo
echo "  # Get blocks with pagination (page 2, size 20)"
echo "  curl $BASE_URL/api/v1/blocks/epoch/12345?page=2&size=20"
echo
echo "  # Get blocks in epoch range 100-200"
echo "  curl $BASE_URL/api/v1/blocks/epoch/range?fromEpoch=100&toEpoch=200"
echo
