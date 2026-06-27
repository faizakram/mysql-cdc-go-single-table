#!/bin/bash

# ============================================================================
# Quick Deploy Script - Single Command Deployment
# ============================================================================
# This script performs a complete deployment in one command:
# 1. Regenerates connector configurations from .env
# 2. Runs deploy-all.sh
# 3. If CDC_ENABLED=false, automatically runs sync-data.py for full-load
# ============================================================================

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}============================================================${NC}"
echo -e "${BLUE}Quick Deploy - All-in-One Deployment${NC}"
echo -e "${BLUE}============================================================${NC}"
echo ""

# Step 1: Load .env and check CDC mode
if [ -f .env ]; then
    set -a  # automatically export all variables
    source .env
    set +a
    echo -e "${GREEN}✅ Loaded .env configuration${NC}"
    echo -e "   CDC_ENABLED: ${CDC_ENABLED}"
    echo ""
else
    echo -e "${YELLOW}⚠️  .env file not found${NC}"
    exit 1
fi

# Step 2: Regenerate connector configurations
echo -e "${BLUE}Step 1/3: Regenerating connector configurations...${NC}"
bash scripts/generate-connectors.sh
echo ""

# Step 3: Run deploy-all.sh
echo -e "${BLUE}Step 2/3: Running deployment...${NC}"
bash scripts/deploy-all.sh
echo ""

# Step 4: If CDC_ENABLED=false, run full-load sync
if [ "${CDC_ENABLED,,}" == "false" ]; then
    echo -e "${BLUE}Step 3/3: Running full-load data synchronization...${NC}"
    
    # Activate virtual environment if it exists
    if [ -d "venv" ]; then
        source venv/bin/activate
    else
        echo -e "${YELLOW}⚠️  Virtual environment not found, using system Python${NC}"
    fi
    
    python3 scripts/sync-data.py
    echo ""
else
    echo -e "${BLUE}Step 3/3: Skipped (CDC mode - data replicates automatically)${NC}"
    echo ""
fi

echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}✅ Quick Deploy Completed Successfully!${NC}"
echo -e "${GREEN}============================================================${NC}"
