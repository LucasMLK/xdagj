#!/bin/bash
# XDAG Test Nodes Update Script
# Automatically build and deploy xdagj.jar to test nodes
# Version: 1.0
# Date: 2025-11-12

set -e

# ==================== Configuration ====================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NODE1_DIR="$SCRIPT_DIR/node1"
NODE2_DIR="$SCRIPT_DIR/node2"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

# ==================== Node Management ====================

stop_node() {
    local node_name="$1"
    local node_dir="$2"

    cd "$node_dir"

    if [ ! -f xdag.pid ]; then
        log_info "$node_name is not running"
        return 0
    fi

    local pid=$(cat xdag.pid)
    if ! kill -0 "$pid" 2>/dev/null; then
        log_info "$node_name is not running (stale PID file)"
        rm -f xdag.pid
        return 0
    fi

    log_info "Stopping $node_name (PID: $pid)..."
    kill "$pid" 2>/dev/null || true

    # Wait for process to exit
    local wait_count=0
    while kill -0 "$pid" 2>/dev/null && [ $wait_count -lt 10 ]; do
        sleep 1
        ((wait_count++))
    done

    # Force kill if still running
    if kill -0 "$pid" 2>/dev/null; then
        log_warning "Force killing $node_name..."
        kill -9 "$pid" 2>/dev/null || true
    fi

    rm -f xdag.pid
    log_success "$node_name stopped"
}

start_node() {
    local node_name="$1"
    local node_dir="$2"

    cd "$node_dir"

    # Check if node is already running
    if [ -f xdag.pid ] && kill -0 "$(cat xdag.pid)" 2>/dev/null; then
        log_warning "$node_name is already running (PID: $(cat xdag.pid))"
        return 0
    fi

    log_info "Starting $node_name..."
    bash start.sh > /dev/null 2>&1

    # Wait for node to start
    local wait_count=0
    while [ $wait_count -lt 30 ]; do
        if [ -f xdag.pid ] && kill -0 "$(cat xdag.pid)" 2>/dev/null; then
            log_success "$node_name started (PID: $(cat xdag.pid))"
            return 0
        fi
        sleep 1
        ((wait_count++))
    done

    log_error "$node_name failed to start"
    return 1
}

# Check if node is ready to accept commands
check_node_ready() {
    local port="$1"
    local password="${2:-root}"
    local timeout=3

    # Check if expect is installed
    if ! command -v expect &> /dev/null; then
        log_warning "expect not installed, skipping readiness check"
        return 0
    fi

    # Try to execute a simple command
    local output=$(expect -c "
        set timeout $timeout
        spawn telnet localhost $port
        expect {
            \"login:\" {
                send \"$password\r\"
                expect \"XDAG>\"
                send \"stats\r\"
                expect \"XDAG>\"
                send \"exit\r\"
                exit 0
            }
            timeout {
                exit 1
            }
        }
    " 2>/dev/null)

    return $?
}

wait_for_node_ready() {
    local node_name="$1"
    local port="$2"
    local max_wait="${3:-60}"  # Default 60 seconds

    log_info "Waiting for $node_name to be ready (port $port)..."

    local elapsed=0
    while [ $elapsed -lt $max_wait ]; do
        if check_node_ready "$port"; then
            log_success "$node_name is ready (took ${elapsed}s)"
            return 0
        fi

        # Show progress every 5 seconds
        if [ $((elapsed % 5)) -eq 0 ]; then
            log_info "  Still waiting... (${elapsed}s/${max_wait}s)"
        fi

        sleep 1
        ((elapsed++))
    done

    log_error "$node_name did not become ready within ${max_wait}s"
    return 1
}

# ==================== Build and Deploy ====================

build_project() {
    log_info "Building project..."
    cd "$PROJECT_ROOT"

    # Check if pom.xml exists
    if [ ! -f pom.xml ]; then
        log_error "pom.xml not found in $PROJECT_ROOT"
        return 1
    fi

    # Run Maven build (skip tests for speed)
    log_info "Running: mvn clean package -DskipTests"
    if mvn clean package -DskipTests -q; then
        log_success "Build completed"
        return 0
    else
        log_error "Build failed"
        return 1
    fi
}

find_jar_file() {
    local jar_file="$PROJECT_ROOT/target/xdagj-*.jar"

    # Find the jar file
    for file in $PROJECT_ROOT/target/xdagj-*.jar; do
        if [ -f "$file" ]; then
            echo "$file"
            return 0
        fi
    done

    log_error "JAR file not found in $PROJECT_ROOT/target/"
    return 1
}

deploy_to_node() {
    local jar_file="$1"
    local node_name="$2"
    local node_dir="$3"

    if [ ! -f "$jar_file" ]; then
        log_error "JAR file not found: $jar_file"
        return 1
    fi

    log_info "Deploying to $node_name..."

    # Copy jar file
    cp "$jar_file" "$node_dir/xdagj.jar"

    log_success "Deployed to $node_name"
    return 0
}

# ==================== Main Functions ====================

update_nodes() {
    local restart_nodes="$1"
    local wait_ready="$2"

    echo "=========================================="
    log_info "XDAG Test Nodes Update"
    echo "=========================================="

    # Step 1: Stop nodes if restart is requested
    if [ "$restart_nodes" == "yes" ]; then
        log_info "Step 1: Stopping nodes..."
        stop_node "Node1" "$NODE1_DIR"
        stop_node "Node2" "$NODE2_DIR"
        sleep 2
    else
        log_info "Step 1: Skipping node stop (nodes will not be restarted)"
    fi

    # Step 2: Build project
    log_info "Step 2: Building project..."
    if ! build_project; then
        log_error "Update failed: Build error"
        return 1
    fi

    # Step 3: Find jar file
    log_info "Step 3: Finding JAR file..."
    jar_file=$(find_jar_file)
    if [ $? -ne 0 ]; then
        log_error "Update failed: JAR file not found"
        return 1
    fi

    log_info "Found JAR: $(basename $jar_file)"

    # Step 4: Deploy to nodes
    log_info "Step 4: Deploying to test nodes..."
    deploy_to_node "$jar_file" "Node1" "$NODE1_DIR"
    deploy_to_node "$jar_file" "Node2" "$NODE2_DIR"

    # Step 5: Start nodes if restart is requested
    if [ "$restart_nodes" == "yes" ]; then
        log_info "Step 5: Starting nodes..."
        start_node "Node1" "$NODE1_DIR"
        start_node "Node2" "$NODE2_DIR"

        # Wait for nodes to initialize
        if [ "$wait_ready" == "yes" ]; then
            log_info "Step 6: Waiting for nodes to be ready..."
            wait_for_node_ready "Node1" 6001 60
            wait_for_node_ready "Node2" 6002 60
        else
            log_info "Waiting for nodes to initialize..."
            sleep 5
        fi
    else
        log_info "Step 5: Skipping node start"
        log_warning "Remember to restart nodes manually: cd test-nodes && ./test-framework.sh (option 3)"
    fi

    echo "=========================================="
    log_success "Update completed successfully"
    echo "=========================================="

    # Show next steps
    if [ "$restart_nodes" == "yes" ]; then
        echo ""
        log_info "Next steps:"
        echo "  1. Check node logs: tail -f node1/logs/xdag-info.log"
        echo "  2. Run tests: ./test-framework.sh run-tests"
        echo "  3. Check status: ./test-framework.sh (option 4)"
        if [ "$wait_ready" == "yes" ]; then
            echo "  4. Compare nodes: ./compare-nodes.sh"
        fi
    else
        echo ""
        log_info "Next steps:"
        echo "  1. Restart nodes: ./test-framework.sh (option 3)"
        echo "  2. Check node logs: tail -f node1/logs/xdag-info.log"
        echo "  3. Run tests: ./test-framework.sh run-tests"
    fi
}

clean_nodes() {
    log_info "Cleaning test node data..."

    # Stop nodes
    stop_node "Node1" "$NODE1_DIR"
    stop_node "Node2" "$NODE2_DIR"

    # Clean data directories
    log_info "Removing data directories..."
    rm -rf "$NODE1_DIR/devnet"
    rm -rf "$NODE1_DIR/logs"
    rm -rf "$NODE2_DIR/devnet"
    rm -rf "$NODE2_DIR/logs"

    log_success "Test node data cleaned"
}

show_help() {
    cat <<EOF
XDAG Test Nodes Update Script

Usage: $0 [OPTIONS]

OPTIONS:
    -h, --help          Show this help message
    -r, --restart       Build, deploy, and restart nodes
    -w, --wait-ready    Wait for nodes to be ready after start (requires -r)
    -n, --no-restart    Build and deploy only (default)
    -c, --clean         Clean node data (stop nodes and remove data directories)

EXAMPLES:
    # Build and deploy (nodes will not be restarted)
    $0

    # Build, deploy, and restart nodes
    $0 --restart

    # Build, deploy, restart, and wait for nodes to be ready
    $0 --restart --wait-ready

    # Clean node data
    $0 --clean

TYPICAL WORKFLOW:
    1. Make code changes in project
    2. Run: $0 --restart --wait-ready
    3. Test: ./test-framework.sh run-tests
    4. Compare: ./compare-nodes.sh

FILES UPDATED:
    - node1/xdagj.jar
    - node2/xdagj.jar

READINESS CHECK:
    When --wait-ready is used, the script will:
    - Wait up to 60 seconds per node
    - Check if telnet admin service responds
    - Verify node accepts commands
    - Show progress every 5 seconds

REQUIREMENTS:
    - expect (for --wait-ready): brew install expect

EOF
}

# ==================== Main Script ====================

main() {
    # Parse command line arguments
    restart_nodes="no"
    wait_ready="no"

    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -r|--restart)
                restart_nodes="yes"
                shift
                ;;
            -w|--wait-ready)
                wait_ready="yes"
                shift
                ;;
            -n|--no-restart)
                restart_nodes="no"
                shift
                ;;
            -c|--clean)
                clean_nodes
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done

    # Validate: --wait-ready requires --restart
    if [ "$wait_ready" == "yes" ] && [ "$restart_nodes" != "yes" ]; then
        log_error "--wait-ready requires --restart"
        echo ""
        echo "Did you mean: $0 --restart --wait-ready"
        exit 1
    fi

    # Run update
    update_nodes "$restart_nodes" "$wait_ready"
}

# Run main function
main "$@"
