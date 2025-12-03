#!/bin/bash

# Transaction Sender Script for XDAG Test Nodes
# Sends a test transaction from one node's wallet to another address

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

# Default values
PASSWORD="test123"
AMOUNT="1.0"

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -f, --from <1|2>      Source node (1 or 2), default: 1"
    echo "  -t, --to <address>    Destination address (Base58)"
    echo "  -a, --amount <xdag>   Amount to send, default: 1.0"
    echo "  -p, --password <pwd>  Wallet password, default: test123"
    echo "  -h, --help            Show this help"
    echo ""
    echo "Examples:"
    echo "  # Send 10 XDAG from Node1 to Node2"
    echo "  $0 -f 1 -t Jwm1mN1QH8nwg14XYp8z8CGripGiGSBhW -a 10"
    echo ""
    echo "  # Send 5 XDAG from Node2 to Node1"
    echo "  $0 -f 2 -t 4AMNsCyLHc9dTqiBB5BHz4AVTSWFEwKtc -a 5"
    echo ""
    echo "Node Addresses:"
    echo "  Node1: 4AMNsCyLHc9dTqiBB5BHz4AVTSWFEwKtc (Port 10001)"
    echo "  Node2: Jwm1mN1QH8nwg14XYp8z8CGripGiGSBhW (Port 10002)"
}

FROM_NODE=1

while [[ $# -gt 0 ]]; do
    case $1 in
        -f|--from)
            FROM_NODE="$2"
            shift 2
            ;;
        -t|--to)
            TO_ADDRESS="$2"
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

if [ -z "$TO_ADDRESS" ]; then
    echo -e "${RED}Error: Destination address is required${NC}"
    usage
    exit 1
fi

# Set wallet and API based on from node
if [ "$FROM_NODE" = "1" ]; then
    WALLET_FILE="$SCRIPT_DIR/suite1/node/devnet/wallet/wallet.data"
    API_URL="http://127.0.0.1:10001"
    NODE_NAME="Node1"
elif [ "$FROM_NODE" = "2" ]; then
    WALLET_FILE="$SCRIPT_DIR/suite2/node/devnet/wallet/wallet.data"
    API_URL="http://127.0.0.1:10002"
    NODE_NAME="Node2"
else
    echo -e "${RED}Error: From node must be 1 or 2${NC}"
    exit 1
fi

# Check if wallet exists
if [ ! -f "$WALLET_FILE" ]; then
    echo -e "${RED}Error: Wallet file not found: $WALLET_FILE${NC}"
    exit 1
fi

echo -e "${CYAN}Sending transaction...${NC}"
echo "  From: $NODE_NAME"
echo "  To: $TO_ADDRESS"
echo "  Amount: $AMOUNT XDAG"
echo ""

# Build classpath
JAR_FILE="$PROJECT_DIR/target/xdagj-1.0.0-executable.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found. Please run 'mvn clean package -DskipTests' first${NC}"
    exit 1
fi

# Run the transaction sender
cd "$PROJECT_DIR"
java -cp "target/classes:target/test-classes:$JAR_FILE" \
    io.xdag.tools.TransactionSender \
    "$WALLET_FILE" "$PASSWORD" "$TO_ADDRESS" "$AMOUNT" "$API_URL"
