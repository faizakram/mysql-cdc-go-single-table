#!/bin/bash

# MySQL CDC Cleanup Script

set -e

echo "🛑 Stopping MySQL CDC Setup"
echo "=========================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

print_step() {
    echo -e "${GREEN}➜${NC} $1"
}

# Stop CDC container
if docker ps -q -f name=mysql-cdc > /dev/null 2>&1; then
    print_step "Stopping CDC container..."
    docker stop mysql-cdc 2>/dev/null || true
    docker rm mysql-cdc 2>/dev/null || true
else
    echo "CDC container not running"
fi

# Stop MySQL containers
print_step "Stopping MySQL databases..."
docker compose stop mysql-source mysql-target

# Ask if user wants to remove volumes (data)
echo ""
read -p "Do you want to remove all data (volumes)? [y/N]: " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_step "Removing volumes and all data..."
    docker compose down -v
    echo "✅ All data removed"
else
    docker compose down
    echo "✅ Data preserved (volumes kept)"
fi

echo ""
echo "✅ Cleanup complete!"
echo ""
echo "To restart: ./start.sh"
