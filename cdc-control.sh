#!/bin/bash

# CDC Control Script - Start, Stop, Restart, and Status management
# Usage: ./cdc-control.sh {start|stop|restart|status|logs}

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CONTAINER_NAME="mysql-cdc"
IMAGE_NAME="mysql-cdc-go-single-table"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
log_info() {
    echo -e "${BLUE}ℹ ${NC}$1"
}

log_success() {
    echo -e "${GREEN}✓${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
    echo -e "${RED}✗${NC} $1"
}

# Function to check if container exists
container_exists() {
    docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"
}

# Function to check if container is running
container_running() {
    docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"
}

# Function to build the image
build_image() {
    log_info "Building Docker image..."
    # Use timestamp as build arg to bust cache and ensure latest code
    CACHEBUST=$(date +%s)
    if docker build --build-arg CACHEBUST=$CACHEBUST -q -t "$IMAGE_NAME" . > /dev/null 2>&1; then
        log_success "Docker image built successfully"
        return 0
    else
        log_error "Failed to build Docker image"
        docker build --build-arg CACHEBUST=$CACHEBUST -t "$IMAGE_NAME" .
        return 1
    fi
}

# Function to start CDC (with auto-resume from checkpoint)
start_cdc() {
    if container_running; then
        log_warning "CDC container is already running"
        return 0
    fi

    if container_exists; then
        log_info "Resuming CDC from existing container..."
        docker start "$CONTAINER_NAME"
        log_success "CDC container resumed"
    else
        log_info "Creating new CDC container (will auto-resume from checkpoint if exists)..."
        ./run-external-db.sh
        sleep 3
        if container_running; then
            log_success "CDC container created and started"
        else
            log_error "Failed to start CDC container"
            return 1
        fi
    fi
}

# Function to resume CDC (same as start, explicitly named for clarity)
resume_cdc() {
    log_info "Resuming CDC (will continue from last checkpoint)..."
    start_cdc
}

# Function to stop CDC
stop_cdc() {
    if ! container_running; then
        log_warning "CDC container is not running"
        if container_exists; then
            log_info "Removing stopped container..."
            docker rm "$CONTAINER_NAME" > /dev/null 2>&1
            log_success "Stopped container removed"
        fi
        return 0
    fi

    log_info "Stopping CDC container..."
    docker stop "$CONTAINER_NAME" > /dev/null 2>&1
    log_success "CDC container stopped"
    
    log_info "Removing container..."
    docker rm "$CONTAINER_NAME" > /dev/null 2>&1
    log_success "Container removed"
}

# Function to restart CDC
restart_cdc() {
    log_info "Restarting CDC (will rebuild and resume from checkpoint)..."
    stop_cdc
    sleep 2
    start_cdc
}

# Function to do a fresh start (drops tables and starts over)
fresh_start() {
    log_warning "Fresh start will DROP target table and checkpoints!"
    read -p "Are you sure? Type 'yes' to confirm: " confirm
    if [ "$confirm" != "yes" ]; then
        log_info "Fresh start cancelled"
        return 1
    fi
    
    log_info "Stopping CDC if running..."
    stop_cdc
    
    log_info "Dropping target table and checkpoints..."
    
    # Read connection details from run-external-db.sh
    source ./run-external-db.sh --dry-run 2>/dev/null || true
    
    # Drop tables
    mysql -h "${TGT_HOST%:*}" -P "${TGT_HOST#*:}" -u"${TGT_USER}" -p"${TGT_PASS}" "${TGT_DB}" -e "
        DROP TABLE IF EXISTS ${TGT_TABLE};
        DROP TABLE IF EXISTS cdc_checkpoints;
        DROP TABLE IF EXISTS full_load_progress;
    " 2>/dev/null
    
    if [ $? -eq 0 ]; then
        log_success "Tables dropped successfully"
    else
        log_error "Failed to drop tables (check credentials in run-external-db.sh)"
        return 1
    fi
    
    log_info "Starting fresh CDC replication..."
    start_cdc
}

# Function to show status
show_status() {
    echo ""
    echo "═══════════════════════════════════════════════════════════"
    echo "           MySQL CDC Status"
    echo "═══════════════════════════════════════════════════════════"
    echo ""
    
    if container_running; then
        log_success "CDC Status: RUNNING"
        echo ""
        
        # Get container info
        echo "Container Details:"
        docker ps --filter "name=${CONTAINER_NAME}" --format "  ID: {{.ID}}\n  Image: {{.Image}}\n  Started: {{.RunningFor}}\n  Status: {{.Status}}"
        echo ""
        
        # Get last checkpoint
        echo "Recent Activity (last 10 lines):"
        docker logs --tail 10 "$CONTAINER_NAME" 2>&1 | sed 's/^/  /'
        echo ""
        
    elif container_exists; then
        log_warning "CDC Status: STOPPED (container exists)"
        echo "  Use './cdc-control.sh start' to start"
        echo ""
    else
        log_error "CDC Status: NOT CREATED"
        echo "  Use './cdc-control.sh start' to create and start"
        echo ""
    fi
    
    echo "═══════════════════════════════════════════════════════════"
}

# Function to show logs
show_logs() {
    if ! container_exists; then
        log_error "CDC container does not exist"
        return 1
    fi
    
    local lines="${2:-50}"
    local follow="${3:-false}"
    
    log_info "Showing last $lines log lines..."
    echo ""
    
    if [ "$follow" = "true" ]; then
        docker logs --tail "$lines" -f "$CONTAINER_NAME" 2>&1
    else
        docker logs --tail "$lines" "$CONTAINER_NAME" 2>&1
    fi
}

# Main command handler
case "${1:-}" in
    start|resume)
        build_image
        start_cdc
        ;;
    stop)
        stop_cdc
        ;;
    restart)
        build_image
        restart_cdc
        ;;
    fresh)
        build_image
        fresh_start
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs "$@"
        ;;
    follow|tail)
        show_logs logs 100 true
        ;;
    build)
        build_image
        ;;
    *)
        echo "MySQL CDC Control Script"
        echo ""
        echo "Usage: $0 {start|resume|stop|restart|fresh|status|logs|follow|build}"
        echo ""
        echo "Commands:"
        echo "  start/resume - Build image and start CDC (auto-resumes from checkpoint)"
        echo "  stop         - Stop and remove CDC container (preserves checkpoints)"
        echo "  restart      - Stop, rebuild, and resume CDC (preserves data)"
        echo "  fresh        - Drop tables and start fresh replication (DESTRUCTIVE)"
        echo "  status       - Show current CDC status and recent activity"
        echo "  logs         - Show last 50 lines of logs"
        echo "  follow       - Follow logs in real-time (Ctrl+C to exit)"
        echo "  build        - Build Docker image only"
        echo ""
        echo "Examples:"
        echo "  $0 start           # Start CDC (resumes if checkpoint exists)"
        echo "  $0 resume          # Same as start - resumes from checkpoint"
        echo "  $0 stop            # Stop CDC (can resume later)"
        echo "  $0 restart         # Restart with latest code (preserves data)"
        echo "  $0 fresh           # Complete fresh start (drops all data)"
        echo "  $0 status          # Check status"
        echo "  $0 follow          # Watch logs live"
        echo ""
        echo "Important:"
        echo "  • 'start/resume/restart' preserve checkpoints and data"
        echo "  • 'fresh' is DESTRUCTIVE - drops target table and starts over"
        echo "  • After 'stop', use 'start' or 'resume' to continue from checkpoint"
        echo ""
        exit 1
        ;;
esac
