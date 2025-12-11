#!/bin/bash
# Container-based deployment script
# This script runs deployment inside a container with all dependencies pre-installed

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${BLUE}ℹ ${1}${NC}"
}

print_success() {
    echo -e "${GREEN}✓ ${1}${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ ${1}${NC}"
}

print_error() {
    echo -e "${RED}✗ ${1}${NC}"
}

print_header() {
    echo ""
    echo -e "${BLUE}============================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}============================================${NC}"
    echo ""
}

# Check if .env file exists
if [ ! -f "$PROJECT_ROOT/.env" ]; then
    print_error ".env file not found!"
    print_info "Please create .env file from .env.example:"
    echo "  cp .env.example .env"
    echo "  # Then edit .env with your configuration"
    exit 1
fi

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    print_error "Docker is not running. Please start Docker first."
    exit 1
fi

# Check if networks exist (infrastructure should be running)
if ! docker network inspect debezium-setup_debezium-network > /dev/null 2>&1; then
    print_warning "Debezium network not found. Starting infrastructure first..."
    cd "$PROJECT_ROOT"
    docker compose up -d
    print_info "Waiting for infrastructure to be ready..."
    sleep 20
fi

# Check if all required networks exist
MISSING_NETWORKS=""
if ! docker network inspect debezium-setup_mssql-network > /dev/null 2>&1; then
    MISSING_NETWORKS="$MISSING_NETWORKS mssql-network"
fi
if ! docker network inspect debezium-setup_postgres-network > /dev/null 2>&1; then
    MISSING_NETWORKS="$MISSING_NETWORKS postgres-network"
fi

if [ ! -z "$MISSING_NETWORKS" ]; then
    print_warning "Required networks not found:$MISSING_NETWORKS"
    print_info "Creating missing networks..."
    
    if ! docker network inspect debezium-setup_mssql-network > /dev/null 2>&1; then
        docker network create debezium-setup_mssql-network
        print_success "Created mssql-network"
    fi
    
    if ! docker network inspect debezium-setup_postgres-network > /dev/null 2>&1; then
        docker network create debezium-setup_postgres-network
        print_success "Created postgres-network"
    fi
fi

print_header "Building Deployment Container"

cd "$PROJECT_ROOT"

# Build the deployer image
print_info "Building deployer image with all dependencies..."
docker build -f Dockerfile.deployer -t cdc-deployer:latest .

if [ $? -eq 0 ]; then
    print_success "Deployer image built successfully"
else
    print_error "Failed to build deployer image"
    exit 1
fi

print_header "Starting Deployment Container"

# Start the deployer container
docker compose -f docker-compose.deployer.yml up -d

if [ $? -eq 0 ]; then
    print_success "Deployment container started"
else
    print_error "Failed to start deployment container"
    exit 1
fi

# Wait a moment for container to be ready
sleep 2

print_header "Running Deployment Script"

# Execute deployment inside the container
print_info "Executing deploy-all.sh inside container..."
echo ""

docker exec -it cdc-deployer bash scripts/deploy-all.sh

DEPLOY_EXIT_CODE=$?

echo ""
if [ $DEPLOY_EXIT_CODE -eq 0 ]; then
    print_success "Deployment completed successfully!"
    echo ""
    print_info "To run CDC management: docker exec -it cdc-deployer bash scripts/manage-cdc.sh"
    print_info "To access container shell: docker exec -it cdc-deployer bash"
    print_info "To stop deployer: docker compose -f docker-compose.deployer.yml down"
else
    print_error "Deployment failed with exit code $DEPLOY_EXIT_CODE"
    echo ""
    print_info "To check logs: docker logs cdc-deployer"
    print_info "To access container: docker exec -it cdc-deployer bash"
    exit $DEPLOY_EXIT_CODE
fi
