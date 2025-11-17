#!/bin/bash
# XDAG Dual Node Log Viewer
# Watch logs from both test nodes simultaneously
# Version: 1.0
# Date: 2025-11-12

set -e

# ==================== Configuration ====================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NODE1_DIR="$SCRIPT_DIR/node1"
NODE2_DIR="$SCRIPT_DIR/node2"
NODE1_LOG="$NODE1_DIR/logs/xdag-info.log"
NODE2_LOG="$NODE2_DIR/logs/xdag-info.log"

# Colors for node differentiation
NODE1_COLOR='\033[0;36m'  # Cyan
NODE2_COLOR='\033[0;35m'  # Magenta
ERROR_COLOR='\033[0;31m'  # Red
WARN_COLOR='\033[1;33m'   # Yellow
INFO_COLOR='\033[0;32m'   # Green
NC='\033[0m'              # No Color

# ==================== Helper Functions ====================

log_info() {
    echo -e "${INFO_COLOR}[INFO]${NC} $1"
}

log_error() {
    echo -e "${ERROR_COLOR}[ERROR]${NC} $1"
}

# ==================== Log Processing ====================

# Add color to log level
colorize_log_level() {
    local line="$1"

    if echo "$line" | grep -q "\[ERROR\]"; then
        echo "$line" | sed "s/\[ERROR\]/${ERROR_COLOR}[ERROR]${NC}/g"
    elif echo "$line" | grep -q "\[WARN\]"; then
        echo "$line" | sed "s/\[WARN\]/${WARN_COLOR}[WARN]${NC}/g"
    elif echo "$line" | grep -q "\[INFO\]"; then
        echo "$line" | sed "s/\[INFO\]/${INFO_COLOR}[INFO]${NC}/g"
    else
        echo "$line"
    fi
}

# Add node prefix and color
format_log_line() {
    local node_name="$1"
    local node_color="$2"
    local line="$3"

    # Skip empty lines
    if [ -z "$line" ]; then
        return
    fi

    # Colorize log level
    local colored_line=$(colorize_log_level "$line")

    # Add node prefix
    echo -e "${node_color}[$node_name]${NC} $colored_line"
}

# ==================== Watch Modes ====================

# Mode 1: Interleaved (default) - Both logs mixed, sorted by time
watch_interleaved() {
    log_info "Watching logs (interleaved mode)..."
    log_info "Press Ctrl+C to exit"
    echo ""

    # Check if logs exist
    if [ ! -f "$NODE1_LOG" ]; then
        log_error "Node1 log not found: $NODE1_LOG"
        exit 1
    fi

    if [ ! -f "$NODE2_LOG" ]; then
        log_error "Node2 log not found: $NODE2_LOG"
        exit 1
    fi

    # Use tail -f on both logs, merge them
    (tail -f "$NODE1_LOG" | while IFS= read -r line; do
        format_log_line "Node1" "$NODE1_COLOR" "$line"
    done) &
    local pid1=$!

    (tail -f "$NODE2_LOG" | while IFS= read -r line; do
        format_log_line "Node2" "$NODE2_COLOR" "$line"
    done) &
    local pid2=$!

    # Trap Ctrl+C to clean up background processes
    trap "kill $pid1 $pid2 2>/dev/null; exit 0" INT TERM

    # Wait for background processes
    wait
}

# Mode 2: Side-by-side - Split screen view
watch_side_by_side() {
    log_info "Watching logs (side-by-side mode)..."
    log_info "Requires: tmux or screen"
    log_info "Press Ctrl+C in each pane to exit"
    echo ""

    # Check if tmux is available
    if command -v tmux &> /dev/null; then
        # Create tmux session with split panes
        tmux new-session -d -s xdag-logs

        # Split window horizontally
        tmux split-window -h -t xdag-logs

        # Left pane: Node1 log
        tmux send-keys -t xdag-logs:0.0 "tail -f $NODE1_LOG" C-m

        # Right pane: Node2 log
        tmux send-keys -t xdag-logs:0.1 "tail -f $NODE2_LOG" C-m

        # Attach to session
        tmux attach-session -t xdag-logs
    else
        log_error "tmux not found. Please install tmux:"
        echo "  macOS: brew install tmux"
        echo "  Linux: apt-get install tmux or yum install tmux"
        echo ""
        log_info "Falling back to interleaved mode..."
        watch_interleaved
    fi
}

# Mode 3: Filter by pattern
watch_filtered() {
    local pattern="$1"

    log_info "Watching logs (filtered: $pattern)..."
    log_info "Press Ctrl+C to exit"
    echo ""

    # Check if logs exist
    if [ ! -f "$NODE1_LOG" ]; then
        log_error "Node1 log not found: $NODE1_LOG"
        exit 1
    fi

    if [ ! -f "$NODE2_LOG" ]; then
        log_error "Node2 log not found: $NODE2_LOG"
        exit 1
    fi

    # Use tail -f with grep filter
    (tail -f "$NODE1_LOG" | grep --line-buffered "$pattern" | while IFS= read -r line; do
        format_log_line "Node1" "$NODE1_COLOR" "$line"
    done) &
    local pid1=$!

    (tail -f "$NODE2_LOG" | grep --line-buffered "$pattern" | while IFS= read -r line; do
        format_log_line "Node2" "$NODE2_COLOR" "$line"
    done) &
    local pid2=$!

    # Trap Ctrl+C
    trap "kill $pid1 $pid2 2>/dev/null; exit 0" INT TERM

    # Wait for background processes
    wait
}

# Mode 4: Show last N lines
show_recent() {
    local lines="${1:-50}"

    echo "=========================================="
    log_info "Node1 - Last $lines lines"
    echo "=========================================="
    tail -n $lines "$NODE1_LOG" | while IFS= read -r line; do
        format_log_line "Node1" "$NODE1_COLOR" "$line"
    done

    echo ""
    echo "=========================================="
    log_info "Node2 - Last $lines lines"
    echo "=========================================="
    tail -n $lines "$NODE2_LOG" | while IFS= read -r line; do
        format_log_line "Node2" "$NODE2_COLOR" "$line"
    done
}

# Mode 5: Follow only errors
watch_errors_only() {
    log_info "Watching errors only..."
    log_info "Press Ctrl+C to exit"
    echo ""

    (tail -f "$NODE1_LOG" | grep --line-buffered -E "\[ERROR\]|\[WARN\]" | while IFS= read -r line; do
        format_log_line "Node1" "$NODE1_COLOR" "$line"
    done) &
    local pid1=$!

    (tail -f "$NODE2_LOG" | grep --line-buffered -E "\[ERROR\]|\[WARN\]" | while IFS= read -r line; do
        format_log_line "Node2" "$NODE2_COLOR" "$line"
    done) &
    local pid2=$!

    trap "kill $pid1 $pid2 2>/dev/null; exit 0" INT TERM
    wait
}

# ==================== Interactive Mode ====================

interactive_mode() {
    while true; do
        echo ""
        echo "=========================================="
        echo "  XDAG Log Viewer - Interactive Mode"
        echo "=========================================="
        echo "1. Watch (Interleaved)"
        echo "2. Watch (Side-by-side with tmux)"
        echo "3. Watch (Errors only)"
        echo "4. Show Recent (Last 50 lines)"
        echo "5. Search Pattern"
        echo "6. Clear Screen"
        echo "7. Exit"
        echo "=========================================="
        read -p "Select option (1-7): " choice

        case $choice in
            1)
                watch_interleaved
                ;;
            2)
                watch_side_by_side
                ;;
            3)
                watch_errors_only
                ;;
            4)
                show_recent 50
                ;;
            5)
                read -p "Enter search pattern (e.g., 'Block imported'): " pattern
                watch_filtered "$pattern"
                ;;
            6)
                clear
                ;;
            7)
                log_info "Exiting..."
                exit 0
                ;;
            *)
                log_error "Invalid option"
                ;;
        esac
    done
}

# ==================== Main Script ====================

show_help() {
    cat <<EOF
XDAG Dual Node Log Viewer

Usage: $0 [OPTIONS]

OPTIONS:
    -h, --help              Show this help message
    -i, --interactive       Interactive mode (default)
    -w, --watch             Watch logs (interleaved mode)
    -s, --side-by-side      Watch logs (side-by-side with tmux)
    -e, --errors            Watch errors only
    -f, --filter PATTERN    Filter logs by pattern
    -n, --lines N           Show last N lines (default: 50)

EXAMPLES:
    # Interactive mode
    $0

    # Watch interleaved
    $0 --watch

    # Watch side-by-side (requires tmux)
    $0 --side-by-side

    # Watch errors only
    $0 --errors

    # Filter by pattern
    $0 --filter "Block imported"

    # Show last 100 lines
    $0 --lines 100

TIPS:
    - Use Ctrl+C to exit watch mode
    - Install tmux for side-by-side view: brew install tmux
    - Logs are color-coded: Node1 (cyan), Node2 (magenta)
    - Error levels: ERROR (red), WARN (yellow), INFO (green)

LOG LOCATIONS:
    Node1: $NODE1_LOG
    Node2: $NODE2_LOG

EOF
}

main() {
    local mode="interactive"
    local filter_pattern=""
    local show_lines=50

    # Parse arguments
    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -i|--interactive)
                mode="interactive"
                shift
                ;;
            -w|--watch)
                mode="watch"
                shift
                ;;
            -s|--side-by-side)
                mode="side-by-side"
                shift
                ;;
            -e|--errors)
                mode="errors"
                shift
                ;;
            -f|--filter)
                mode="filter"
                filter_pattern="$2"
                shift 2
                ;;
            -n|--lines)
                mode="recent"
                show_lines="$2"
                shift 2
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done

    # Check if log files exist
    if [ ! -f "$NODE1_LOG" ] && [ ! -f "$NODE2_LOG" ]; then
        log_error "No log files found. Are the nodes running?"
        log_info "Start nodes with: ./update-nodes.sh --restart"
        exit 1
    fi

    # Run appropriate mode
    case "$mode" in
        interactive)
            interactive_mode
            ;;
        watch)
            watch_interleaved
            ;;
        side-by-side)
            watch_side_by_side
            ;;
        errors)
            watch_errors_only
            ;;
        filter)
            watch_filtered "$filter_pattern"
            ;;
        recent)
            show_recent "$show_lines"
            ;;
        *)
            log_error "Unknown mode: $mode"
            exit 1
            ;;
    esac
}

# Run main function
main "$@"
