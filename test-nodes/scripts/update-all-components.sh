#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_NODES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$TEST_NODES_DIR/.." && pwd)"

# Paths to external repos (assuming they are siblings of xdagj or in the same root)
# The user said "code directory are all in /Users/reymondtu/dev/github"
# And current dir is /Users/reymondtu/dev/github/xdagj
# So xdagj-pool is ../xdagj-pool
POOL_DIR="$ROOT_DIR/../xdagj-pool"
MINER_DIR="$ROOT_DIR/../xdagj-miner"

log() {
    echo -e "\033[0;32m[UPDATE]\033[0m $1"
}

error() {
    echo -e "\033[0;31m[ERROR]\033[0m $1"
}

# 1. Update XDAGJ Node
log "Updating XDAGJ Node..."
cd "$TEST_NODES_DIR"
./scripts/update-nodes.sh --no-restart
if [ $? -ne 0 ]; then
    error "Failed to update XDAGJ Node"
    exit 1
fi

# 2. Update XDAGJ Pool
log "Updating XDAGJ Pool..."
if [ -d "$POOL_DIR" ]; then
    cd "$POOL_DIR"
    log "Building Pool in $POOL_DIR..."
    mvn clean package -DskipTests -q
    
    # Find jar
    POOL_JAR=$(find target -name "xdagj-pool-*-jar-with-dependencies.jar" | head -n 1)
    if [ -f "$POOL_JAR" ]; then
        log "Found Pool JAR: $POOL_JAR"
        cp "$POOL_JAR" "$TEST_NODES_DIR/suite1/pool/xdagj-pool.jar"
        cp "$POOL_JAR" "$TEST_NODES_DIR/suite2/pool/xdagj-pool.jar"
        log "Copied Pool JAR to suites"
    else
        error "Pool JAR not found in target/"
        exit 1
    fi
else
    error "XDAGJ Pool directory not found at $POOL_DIR"
    exit 1
fi

# 3. Update XDAGJ Miner
log "Updating XDAGJ Miner..."
if [ -d "$MINER_DIR" ]; then
    cd "$MINER_DIR"
    log "Building Miner in $MINER_DIR..."
    mvn clean package -DskipTests -q
    
    # Find jar
    MINER_JAR=$(find target -name "xdagj-miner-*-jar-with-dependencies.jar" | head -n 1)
    if [ -f "$MINER_JAR" ]; then
        log "Found Miner JAR: $MINER_JAR"
        cp "$MINER_JAR" "$TEST_NODES_DIR/suite1/miner/xdagj-miner.jar"
        cp "$MINER_JAR" "$TEST_NODES_DIR/suite2/miner/xdagj-miner.jar"
        log "Copied Miner JAR to suites"
    else
        error "Miner JAR not found in target/"
        exit 1
    fi
else
    error "XDAGJ Miner directory not found at $MINER_DIR"
    exit 1
fi

log "All components updated successfully!"
