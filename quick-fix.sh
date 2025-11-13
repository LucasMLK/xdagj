#!/bin/bash
# Quick Fix Script for XDAG Refactoring
# Automates simple, safe refactoring tasks

set -e

echo "========================================="
echo "XDAG Quick Refactoring Script"
echo "========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
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

# Check if we're in the project root
if [ ! -f "pom.xml" ]; then
    log_error "This script must be run from the project root directory"
    exit 1
fi

# Create backup
create_backup() {
    log_info "Creating backup branch..."
    BACKUP_BRANCH="backup/pre-quick-fixes-$(date +%Y%m%d_%H%M%S)"
    git branch "$BACKUP_BRANCH"
    log_success "Backup branch created: $BACKUP_BRANCH"
}

# Task 1: Fix execption → exception typo
fix_exception_typo() {
    log_info "Task 1: Fixing 'execption' → 'exception' typo..."

    if [ -d "src/main/java/io/xdag/db/execption" ]; then
        # Rename directory
        mv src/main/java/io/xdag/db/execption src/main/java/io/xdag/db/exception
        log_success "Renamed directory: execption → exception"

        # Update imports in all Java files
        find src -name "*.java" -type f -exec sed -i '' 's/io\.xdag\.db\.execption/io.xdag.db.exception/g' {} \;
        log_success "Updated imports in all Java files"
    else
        log_warning "Directory 'execption' not found, may already be fixed"
    fi
}

# Task 2: Remove dead pool code
remove_pool_code() {
    log_info "Task 2: Removing dead pool code..."

    if [ -d "src/main/java/io/xdag/pool" ]; then
        # Check if code is commented out
        if grep -q "/\*" src/main/java/io/xdag/pool/*.java 2>/dev/null; then
            log_warning "Pool code appears to be commented out"
            read -p "Do you want to delete the pool package? (y/N) " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                rm -rf src/main/java/io/xdag/pool
                log_success "Deleted pool package"
            else
                log_info "Skipping pool package deletion"
            fi
        else
            log_warning "Pool code may be active, skipping deletion"
        fi
    else
        log_warning "Pool package not found, may already be removed"
    fi
}

# Task 3: Move test utilities to test directory
move_test_utils() {
    log_info "Task 3: Moving test utilities..."

    if [ -f "src/main/java/io/xdag/utils/CreateTestWallet.java" ]; then
        # Create target directory
        mkdir -p src/test/java/io/xdag/utils

        # Move file
        mv src/main/java/io/xdag/utils/CreateTestWallet.java \
           src/test/java/io/xdag/utils/CreateTestWallet.java

        log_success "Moved CreateTestWallet.java to test directory"
    else
        log_warning "CreateTestWallet.java not found or already moved"
    fi
}

# Task 4: Remove empty directories
remove_empty_dirs() {
    log_info "Task 4: Removing empty directories..."

    EMPTY_DIRS=$(find src/main/java/io/xdag -type d -empty)
    if [ -n "$EMPTY_DIRS" ]; then
        echo "$EMPTY_DIRS" | while read -r dir; do
            rmdir "$dir"
            log_success "Removed empty directory: $dir"
        done
    else
        log_info "No empty directories found"
    fi
}

# Compile check
compile_check() {
    log_info "Running compilation check..."

    if mvn clean compile -DskipTests -q; then
        log_success "Compilation successful!"
        return 0
    else
        log_error "Compilation failed!"
        return 1
    fi
}

# Test check
test_check() {
    log_info "Running tests..."

    if mvn test -q; then
        log_success "All tests passed!"
        return 0
    else
        log_warning "Some tests failed, check output"
        return 1
    fi
}

# Show git status
show_status() {
    log_info "Git status:"
    echo ""
    git status --short
    echo ""
}

# Main menu
main_menu() {
    echo ""
    echo "Quick Fix Options:"
    echo "1. Run all quick fixes (Tasks 1-4)"
    echo "2. Fix 'execption' typo only"
    echo "3. Remove pool code only"
    echo "4. Move test utilities only"
    echo "5. Remove empty directories"
    echo "6. Compile check"
    echo "7. Run tests"
    echo "8. Show git status"
    echo "9. Exit"
    echo ""
    read -p "Select option (1-9): " option

    case $option in
        1)
            create_backup
            fix_exception_typo
            remove_pool_code
            move_test_utils
            remove_empty_dirs
            show_status
            compile_check
            ;;
        2)
            create_backup
            fix_exception_typo
            show_status
            compile_check
            ;;
        3)
            create_backup
            remove_pool_code
            show_status
            compile_check
            ;;
        4)
            create_backup
            move_test_utils
            show_status
            compile_check
            ;;
        5)
            remove_empty_dirs
            show_status
            ;;
        6)
            compile_check
            ;;
        7)
            test_check
            ;;
        8)
            show_status
            ;;
        9)
            log_info "Exiting..."
            exit 0
            ;;
        *)
            log_error "Invalid option"
            main_menu
            ;;
    esac
}

# Run main menu
main_menu

# Ask if user wants to commit changes
echo ""
read -p "Do you want to commit these changes? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    log_info "Creating commit..."
    git add -A
    git commit -m "refactor: Quick fixes from refactoring plan

- Fix 'execption' → 'exception' typo
- Remove dead pool code (if selected)
- Move test utilities to test directory
- Remove empty directories

Part of comprehensive refactoring plan (see REFACTORING_PLAN.md)

🤖 Automated with quick-fix script"
    log_success "Changes committed!"
else
    log_info "Changes not committed, you can review and commit manually"
fi

echo ""
log_success "Quick fixes completed!"
log_info "For more complex refactoring, see REFACTORING_PLAN.md"
echo ""
