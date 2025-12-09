#!/bin/bash

# XDAG Transaction Stress Test Script
# Sends 200 transactions per epoch (64 seconds) to stress test the network

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuration
EPOCH_DURATION=64           # seconds per epoch
TX_PER_EPOCH=200           # transactions per epoch
PASSWORD="test123"
AMOUNT="0.001"             # Small amount per transaction

# Node configurations
NODE1_WALLET="$SCRIPT_DIR/suite1/node/devnet/wallet/wallet.data"
NODE1_API="http://127.0.0.1:10001"
NODE1_ADDR="4AMNsCyLHc9dTqiBB5BHz4AVTSWFEwKtc"

NODE2_WALLET="$SCRIPT_DIR/suite2/node/devnet/wallet/wallet.data"
NODE2_API="http://127.0.0.1:10002"
NODE2_ADDR="Jwm1mN1QH8nwg14XYp8z8CGripGiGSBhW"

JAR_FILE="$PROJECT_DIR/target/xdagj-1.0.0-executable.jar"

# Statistics
TOTAL_TX=0
SUCCESS_TX=0
FAILED_TX=0
EPOCH_COUNT=0

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -e, --epochs <num>    Number of epochs to run (default: infinite)"
    echo "  -n, --tx-count <num>  Transactions per epoch (default: 200)"
    echo "  -a, --amount <xdag>   Amount per transaction (default: 0.001)"
    echo "  -p, --password <pwd>  Wallet password (default: test123)"
    echo "  -h, --help            Show this help"
    echo ""
    echo "Examples:"
    echo "  # Run for 5 epochs with 200 tx each"
    echo "  $0 -e 5"
    echo ""
    echo "  # Run continuously with 100 tx per epoch"
    echo "  $0 -n 100"
}

MAX_EPOCHS=0  # 0 means infinite

while [[ $# -gt 0 ]]; do
    case $1 in
        -e|--epochs)
            MAX_EPOCHS="$2"
            shift 2
            ;;
        -n|--tx-count)
            TX_PER_EPOCH="$2"
            shift 2
            ;;
        -a|--amount)
            AMOUNT="$2"
            shift 2
            ;;
        -p|--password)
            PASSWORD="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            exit 1
            ;;
    esac
done

# Check prerequisites
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found. Please run 'mvn clean package -DskipTests' first${NC}"
    exit 1
fi

if [ ! -f "$NODE1_WALLET" ] || [ ! -f "$NODE2_WALLET" ]; then
    echo -e "${RED}Error: Wallet files not found. Make sure nodes have been initialized.${NC}"
    exit 1
fi

# Check if nodes are running
check_node() {
    local api=$1
    local name=$2
    if ! curl -s "$api/api/v1/blocks/number" > /dev/null 2>&1; then
        echo -e "${RED}Error: $name is not responding at $api${NC}"
        return 1
    fi
    return 0
}

check_node "$NODE1_API" "Node1" || exit 1
check_node "$NODE2_API" "Node2" || exit 1

echo -e "${GREEN}=== XDAG Transaction Stress Test ===${NC}"
echo "Transactions per epoch: $TX_PER_EPOCH"
echo "Amount per transaction: $AMOUNT XDAG"
echo "Max epochs: $([ $MAX_EPOCHS -eq 0 ] && echo 'infinite' || echo $MAX_EPOCHS)"
echo ""

# Send a single transaction
send_tx() {
    local wallet=$1
    local to_addr=$2
    local api=$3
    local amount=$4

    cd "$PROJECT_DIR"
    result=$(java -cp "target/classes:target/test-classes:$JAR_FILE" \
        io.xdag.tools.TransactionSender \
        "$wallet" "$PASSWORD" "$to_addr" "$amount" "$api" 2>&1)

    if echo "$result" | grep -q "transactionHash"; then
        return 0
    else
        echo "$result" | tail -1
        return 1
    fi
}

# Print statistics
print_stats() {
    local rate=0
    if [ $TOTAL_TX -gt 0 ]; then
        rate=$((SUCCESS_TX * 100 / TOTAL_TX))
    fi
    echo -e "${CYAN}Stats: ${GREEN}$SUCCESS_TX success${NC} / ${RED}$FAILED_TX failed${NC} / Total: $TOTAL_TX (${rate}% success rate)"
}

# Cleanup on exit
cleanup() {
    echo ""
    echo -e "${YELLOW}=== Final Statistics ===${NC}"
    echo "Epochs completed: $EPOCH_COUNT"
    print_stats
    exit 0
}
trap cleanup SIGINT SIGTERM

# Calculate interval between transactions
# TX_PER_EPOCH transactions in EPOCH_DURATION seconds
INTERVAL=$(echo "scale=3; $EPOCH_DURATION / $TX_PER_EPOCH" | bc)

echo -e "${CYAN}Sending 1 transaction every ${INTERVAL}s${NC}"
echo ""

# Main loop
while true; do
    EPOCH_START=$(date +%s)
    EPOCH_COUNT=$((EPOCH_COUNT + 1))
    EPOCH_SUCCESS=0
    EPOCH_FAILED=0

    echo -e "${YELLOW}=== Epoch $EPOCH_COUNT starting ===${NC}"

    for ((i=1; i<=TX_PER_EPOCH; i++)); do
        # Alternate between Node1->Node2 and Node2->Node1
        if [ $((i % 2)) -eq 1 ]; then
            wallet="$NODE1_WALLET"
            to_addr="$NODE2_ADDR"
            api="$NODE1_API"
            direction="N1→N2"
        else
            wallet="$NODE2_WALLET"
            to_addr="$NODE1_ADDR"
            api="$NODE2_API"
            direction="N2→N1"
        fi

        TOTAL_TX=$((TOTAL_TX + 1))

        if send_tx "$wallet" "$to_addr" "$api" "$AMOUNT"; then
            SUCCESS_TX=$((SUCCESS_TX + 1))
            EPOCH_SUCCESS=$((EPOCH_SUCCESS + 1))
            printf "\r[Epoch $EPOCH_COUNT] TX $i/$TX_PER_EPOCH $direction ${GREEN}OK${NC}     "
        else
            FAILED_TX=$((FAILED_TX + 1))
            EPOCH_FAILED=$((EPOCH_FAILED + 1))
            printf "\r[Epoch $EPOCH_COUNT] TX $i/$TX_PER_EPOCH $direction ${RED}FAIL${NC}   "
        fi

        # Wait for next transaction (but not after last one)
        if [ $i -lt $TX_PER_EPOCH ]; then
            sleep "$INTERVAL"
        fi
    done

    echo ""
    echo -e "Epoch $EPOCH_COUNT complete: ${GREEN}$EPOCH_SUCCESS success${NC}, ${RED}$EPOCH_FAILED failed${NC}"
    print_stats
    echo ""

    # Check if we've reached max epochs
    if [ $MAX_EPOCHS -gt 0 ] && [ $EPOCH_COUNT -ge $MAX_EPOCHS ]; then
        echo -e "${GREEN}Reached max epochs ($MAX_EPOCHS)${NC}"
        break
    fi

    # Wait for remaining time in epoch
    EPOCH_ELAPSED=$(($(date +%s) - EPOCH_START))
    if [ $EPOCH_ELAPSED -lt $EPOCH_DURATION ]; then
        WAIT_TIME=$((EPOCH_DURATION - EPOCH_ELAPSED))
        echo -e "${CYAN}Waiting ${WAIT_TIME}s for next epoch...${NC}"
        sleep $WAIT_TIME
    fi
done

cleanup
