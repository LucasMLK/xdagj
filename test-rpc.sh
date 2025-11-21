#!/bin/bash

# XDAG HTTP API Test Script
# Tests HTTP RESTful API endpoints

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="http://127.0.0.1:10001"
REST_URL="$BASE_URL/api/v1"

# Optional: Set API key for authenticated requests
# API_KEY="your-api-key-here"

# Test counter
TOTAL_TESTS=0
PASSED_TESTS=0

echo "=========================================================="
echo "XDAG HTTP API Test Suite"
echo "Testing RESTful API Endpoints"
echo "=========================================================="
echo ""

# Function to test RESTful endpoint
test_rest() {
    local endpoint=$1
    local description=$2

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -ne "${BLUE}Test $TOTAL_TESTS:${NC} $description... "

    # Add Authorization header if API_KEY is set
    local auth_header=""
    if [ -n "$API_KEY" ]; then
        auth_header="-H \"Authorization: Bearer $API_KEY\""
    fi

    local response=$(curl -s $auth_header "$REST_URL$endpoint" 2>/dev/null)

    if [ -z "$response" ]; then
        echo -e "${RED}FAILED${NC} (No response)"
        return 1
    fi

    if echo "$response" | grep -q '"error"'; then
        echo -e "${RED}FAILED${NC}"
        echo "  Error: $(echo "$response" | jq -r '.error' 2>/dev/null || echo "$response")"
        return 1
    fi

    echo -e "${GREEN}PASSED${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
    echo "  Response: $(echo "$response" | jq -c '.' 2>/dev/null | head -c 100)..."
    return 0
}


echo "${YELLOW}========================================${NC}"
echo "${YELLOW}RESTful API Tests${NC}"
echo "${YELLOW}========================================${NC}"
echo ""

echo "--- Node Information ---"
test_rest "/network/protocol" "Get protocol version"
test_rest "/network/chainId" "Get chain ID"
test_rest "/network/coinbase" "Get coinbase address"
test_rest "/network/syncing" "Get sync status"
test_rest "/network/peers/count" "Get peer count"

echo ""
echo "--- Block Queries ---"
test_rest "/blocks/number" "Get current block number"
test_rest "/blocks/latest?fullTransactions=false" "Get latest block"
test_rest "/blocks/0?fullTransactions=false" "Get genesis block"
test_rest "/blocks?page=1&size=5" "List latest blocks (paged)"

echo ""
echo "--- Transaction Queries ---"
test_rest "/transactions?page=1&size=5" "List recent transactions (paged)"

echo ""
echo "--- Account Queries ---"
test_rest "/accounts" "Get wallet accounts"

echo ""
echo "--- Documentation Endpoints ---"
echo -ne "${BLUE}Test $((TOTAL_TESTS + 1)):${NC} OpenAPI spec availability... "
OPENAPI_RESPONSE=$(curl -s "$BASE_URL/openapi.yaml" -o /dev/null -w "%{http_code}")
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if [ "$OPENAPI_RESPONSE" = "200" ]; then
    echo -e "${GREEN}PASSED${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
    echo "  Response: HTTP $OPENAPI_RESPONSE"
else
    echo -e "${RED}FAILED${NC} (HTTP $OPENAPI_RESPONSE)"
fi

echo ""
echo "=========================================================="
echo "Test Summary"
echo "=========================================================="
echo "Total Tests: $TOTAL_TESTS"
echo -e "Passed: ${GREEN}$PASSED_TESTS${NC}"
echo -e "Failed: ${RED}$((TOTAL_TESTS - PASSED_TESTS))${NC}"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    echo ""
    echo "Available endpoints:"
    echo "  - RESTful API:  $REST_URL/"
    echo "  - OpenAPI Spec: $BASE_URL/openapi.yaml"
    echo "  - API Docs:     $BASE_URL/docs"
    exit 0
else
    echo -e "${RED}✗ Some tests failed${NC}"
    exit 1
fi
