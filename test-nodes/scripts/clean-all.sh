#!/bin/bash

# XDAG Test Environment - Clean All Data
# Cleans RocksDB databases, logs, and PID files for Suite1 and Suite2
# Version: 1.0
# Date: 2025-11-17

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NODES_DIR="$(dirname "$SCRIPT_DIR")"
NODE_PID_FILE="xdag.pid"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if services are running
check_running_services() {
    local running=0

    if pgrep -f "xdagj.jar" > /dev/null 2>&1; then
        log_warn "Node processes are still running"
        running=1
    fi

    if pgrep -f "xdagj-pool" > /dev/null 2>&1; then
        log_warn "Pool processes are still running"
        running=1
    fi

    if pgrep -f "xdagj-miner" > /dev/null 2>&1; then
        log_warn "Miner processes are still running"
        running=1
    fi

    return $running
}

# Clean RocksDB databases
clean_rocksdb() {
    log_info "Cleaning RocksDB databases..."

    local cleaned=0

    clean_suite_node_db() {
        local suite=$1
        local devnet_dir="$TEST_NODES_DIR/$suite/node/devnet"
        local rocksdb_dir="$devnet_dir/rocksdb"
        local reputation_dir="$devnet_dir/reputation"

        if [ -d "$rocksdb_dir" ]; then
            rm -rf "$rocksdb_dir"
            mkdir -p "$rocksdb_dir"
            log_success "$suite RocksDB directory cleaned"
            cleaned=$((cleaned + 1))
        fi

        if [ -d "$reputation_dir" ]; then
            rm -rf "$reputation_dir"
            mkdir -p "$reputation_dir"
            log_success "$suite reputation cache cleaned"
            cleaned=$((cleaned + 1))
        fi
    }

    clean_suite_node_db "suite1"
    clean_suite_node_db "suite2"

    if [ $cleaned -eq 0 ]; then
        log_info "No RocksDB data found (already clean)"
    else
        log_success "Cleaned $cleaned directory items (rocksdb/reputation)"
    fi
}

# Clean log files
clean_logs() {
    log_info "Cleaning log files..."

    local cleaned=0

    # Clean Suite1 logs
    if [ -d "$TEST_NODES_DIR/suite1/node/logs" ]; then
        rm -rf "$TEST_NODES_DIR/suite1/node/logs"
        log_success "Suite1 node logs/ directory cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -f "$TEST_NODES_DIR/suite1/node/node1.log" ]; then
        rm -f "$TEST_NODES_DIR/suite1/node/node1.log"
        log_success "Suite1 node1.log cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -d "$TEST_NODES_DIR/suite1/pool/logs" ]; then
        rm -rf "$TEST_NODES_DIR/suite1/pool/logs"
        log_success "Suite1 pool logs/ directory cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -f "$TEST_NODES_DIR/suite1/pool/pool.log" ]; then
        rm -f "$TEST_NODES_DIR/suite1/pool/pool.log"
        log_success "Suite1 pool.log cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -d "$TEST_NODES_DIR/suite1/miner/logs" ]; then
        rm -rf "$TEST_NODES_DIR/suite1/miner/logs"
        log_success "Suite1 miner logs/ directory cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -f "$TEST_NODES_DIR/suite1/miner/miner.log" ]; then
        rm -f "$TEST_NODES_DIR/suite1/miner/miner.log"
        log_success "Suite1 miner.log cleaned"
        cleaned=$((cleaned + 1))
    fi

    # Clean Suite1 JVM crash logs
    if ls "$TEST_NODES_DIR/suite1/miner/hs_err_pid"*.log 1> /dev/null 2>&1; then
        rm -f "$TEST_NODES_DIR/suite1/miner/hs_err_pid"*.log
        log_success "Suite1 miner JVM crash logs cleaned"
        cleaned=$((cleaned + 1))
    fi

    if ls "$TEST_NODES_DIR/suite1/pool/hs_err_pid"*.log 1> /dev/null 2>&1; then
        rm -f "$TEST_NODES_DIR/suite1/pool/hs_err_pid"*.log
        log_success "Suite1 pool JVM crash logs cleaned"
        cleaned=$((cleaned + 1))
    fi

    if ls "$TEST_NODES_DIR/suite1/node/hs_err_pid"*.log 1> /dev/null 2>&1; then
        rm -f "$TEST_NODES_DIR/suite1/node/hs_err_pid"*.log
        log_success "Suite1 node JVM crash logs cleaned"
        cleaned=$((cleaned + 1))
    fi

    # Clean Suite2 logs
    if [ -d "$TEST_NODES_DIR/suite2/node/logs" ]; then
        rm -rf "$TEST_NODES_DIR/suite2/node/logs"
        log_success "Suite2 node logs/ directory cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -f "$TEST_NODES_DIR/suite2/node/node2.log" ]; then
        rm -f "$TEST_NODES_DIR/suite2/node/node2.log"
        log_success "Suite2 node2.log cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -d "$TEST_NODES_DIR/suite2/pool/logs" ]; then
        rm -rf "$TEST_NODES_DIR/suite2/pool/logs"
        log_success "Suite2 pool logs/ directory cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -f "$TEST_NODES_DIR/suite2/pool/pool.log" ]; then
        rm -f "$TEST_NODES_DIR/suite2/pool/pool.log"
        log_success "Suite2 pool.log cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -d "$TEST_NODES_DIR/suite2/miner/logs" ]; then
        rm -rf "$TEST_NODES_DIR/suite2/miner/logs"
        log_success "Suite2 miner logs/ directory cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ -f "$TEST_NODES_DIR/suite2/miner/miner.log" ]; then
        rm -f "$TEST_NODES_DIR/suite2/miner/miner.log"
        log_success "Suite2 miner.log cleaned"
        cleaned=$((cleaned + 1))
    fi

    # Clean Suite2 JVM crash logs
    if ls "$TEST_NODES_DIR/suite2/miner/hs_err_pid"*.log 1> /dev/null 2>&1; then
        rm -f "$TEST_NODES_DIR/suite2/miner/hs_err_pid"*.log
        log_success "Suite2 miner JVM crash logs cleaned"
        cleaned=$((cleaned + 1))
    fi

    if ls "$TEST_NODES_DIR/suite2/pool/hs_err_pid"*.log 1> /dev/null 2>&1; then
        rm -f "$TEST_NODES_DIR/suite2/pool/hs_err_pid"*.log
        log_success "Suite2 pool JVM crash logs cleaned"
        cleaned=$((cleaned + 1))
    fi

    if ls "$TEST_NODES_DIR/suite2/node/hs_err_pid"*.log 1> /dev/null 2>&1; then
        rm -f "$TEST_NODES_DIR/suite2/node/hs_err_pid"*.log
        log_success "Suite2 node JVM crash logs cleaned"
        cleaned=$((cleaned + 1))
    fi

    if [ $cleaned -eq 0 ]; then
        log_info "No log files found (already clean)"
    else
        log_success "Cleaned $cleaned log file(s) and directories"
    fi
}

# Clean PID files
clean_pids() {
    log_info "Cleaning PID files..."

    local cleaned=0

    # Clean Suite1 PIDs
    if [ -f "$TEST_NODES_DIR/suite1/node/$NODE_PID_FILE" ]; then
        rm -f "$TEST_NODES_DIR/suite1/node/$NODE_PID_FILE"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite1/node/xdagj.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite1/node/xdagj.pid"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite1/node/node.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite1/node/node.pid"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite1/pool/pool.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite1/pool/pool.pid"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite1/miner/miner.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite1/miner/miner.pid"
        cleaned=$((cleaned + 1))
    fi

    # Clean Suite2 PIDs
    if [ -f "$TEST_NODES_DIR/suite2/node/$NODE_PID_FILE" ]; then
        rm -f "$TEST_NODES_DIR/suite2/node/$NODE_PID_FILE"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite2/node/xdagj.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite2/node/xdagj.pid"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite2/node/node.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite2/node/node.pid"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite2/pool/pool.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite2/pool/pool.pid"
        cleaned=$((cleaned + 1))
    fi
    if [ -f "$TEST_NODES_DIR/suite2/miner/miner.pid" ]; then
        rm -f "$TEST_NODES_DIR/suite2/miner/miner.pid"
        cleaned=$((cleaned + 1))
    fi

    if [ $cleaned -eq 0 ]; then
        log_info "No PID files found (already clean)"
    else
        log_success "Cleaned $cleaned PID file(s)"
    fi
}

# Show disk space saved
show_disk_space() {
    log_info "Checking disk space..."

    # Calculate total size of what will be cleaned
    local total_size=0

    if command -v du > /dev/null 2>&1; then
        for dir in "$TEST_NODES_DIR/suite1/node/devnet" \
                   "$TEST_NODES_DIR/suite2/node/devnet" \
                   "$TEST_NODES_DIR/suite1/node/logs" \
                   "$TEST_NODES_DIR/suite2/node/logs"; do
            if [ -d "$dir" ]; then
                local size=$(du -sh "$dir" 2>/dev/null | awk '{print $1}')
                log_info "  $dir: $size"
            fi
        done
    fi
}

show_help() {
    cat <<EOF
XDAG Test Environment - Clean All Data Script

Usage: $0 [OPTIONS]

OPTIONS:
    -h, --help           Show this help message
    -a, --all            Clean everything (databases + logs + PIDs) [default]
    -d, --database       Clean only RocksDB databases
    -l, --logs           Clean only log files
    -p, --pids           Clean only PID files
    -f, --force          Force clean without stopping services (dangerous!)
    --dry-run            Show what would be cleaned without actually cleaning

EXAMPLES:
    # Clean everything (stop services first!)
    $0

    # Clean only databases
    $0 --database

    # Clean only logs
    $0 --logs

    # Force clean without stopping (use with caution)
    $0 --force

    # See what would be cleaned
    $0 --dry-run

WHAT GETS CLEANED:
    Databases:
      - suite1/node/devnet/       (RocksDB blockchain data)
      - suite2/node/devnet/       (RocksDB blockchain data)

    Logs:
      - suite1/node/logs/         (Node logs)
      - suite2/node/logs/         (Node logs)
      - suite1/pool/pool.log      (Pool logs)
      - suite2/pool/pool.log      (Pool logs)
      - suite1/miner/miner.log    (Miner logs)
      - suite2/miner/miner.log    (Miner logs)

    PIDs:
      - All .pid files in node/pool/miner directories

TYPICAL WORKFLOW:
    1. Stop services:  ./scripts/stop-all.sh
    2. Clean data:     ./scripts/clean-all.sh
    3. Update nodes:   ./scripts/update-nodes.sh --restart
    4. Start services: ./scripts/start-all.sh

WARNING:
    - ALWAYS stop services before cleaning
    - Cleaning databases will DESTROY all blockchain data
    - Cleaning logs will DESTROY all historical logs
    - This operation cannot be undone

EOF
}

main() {
    local clean_db=0
    local clean_log=0
    local clean_pid=0
    local force=0
    local dry_run=0

    # Parse command line arguments
    if [ $# -eq 0 ]; then
        # Default: clean everything
        clean_db=1
        clean_log=1
        clean_pid=1
    fi

    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -a|--all)
                clean_db=1
                clean_log=1
                clean_pid=1
                shift
                ;;
            -d|--database)
                clean_db=1
                shift
                ;;
            -l|--logs)
                clean_log=1
                shift
                ;;
            -p|--pids)
                clean_pid=1
                shift
                ;;
            -f|--force)
                force=1
                shift
                ;;
            --dry-run)
                dry_run=1
                shift
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done

    echo "========================================="
    echo "   XDAG Test Environment - Clean All"
    echo "========================================="
    echo ""

    # Dry run mode
    if [ $dry_run -eq 1 ]; then
        log_info "DRY RUN MODE - Nothing will be deleted"
        echo ""
        show_disk_space
        echo ""
        log_info "Run without --dry-run to actually clean"
        exit 0
    fi

    # Safety check: warn if services are running
    if [ $force -eq 0 ]; then
        if check_running_services; then
            echo ""
            log_error "Services are still running!"
            log_error "Please stop all services before cleaning:"
            echo ""
            echo "  ./scripts/stop-all.sh"
            echo ""
            log_warn "Or use --force to clean anyway (not recommended)"
            exit 1
        fi
    else
        log_warn "Force mode enabled - cleaning while services may be running"
    fi

    echo ""

    # Perform cleaning operations
    if [ $clean_db -eq 1 ]; then
        clean_rocksdb
        echo ""
    fi

    if [ $clean_log -eq 1 ]; then
        clean_logs
        echo ""
    fi

    if [ $clean_pid -eq 1 ]; then
        clean_pids
        echo ""
    fi

    echo "========================================="
    log_success "Cleaning completed successfully!"
    echo "========================================="
    echo ""
    echo "Next steps:"
    echo "  1. Update & restart:  ./scripts/update-nodes.sh --restart"
    echo "  2. Or start manually: ./scripts/start-all.sh"
    echo ""
}

main "$@"
