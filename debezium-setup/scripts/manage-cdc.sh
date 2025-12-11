#!/bin/bash

# ========================================================
# Enable CDC on All Tables - Helper Script
# ========================================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================================="
echo "CDC Enablement Script"
echo "========================================================="
echo ""

# Load .env if exists
if [ -f .env ]; then
    source .env
    echo -e "${GREEN}✓${NC} Loaded configuration from .env"
else
    echo -e "${RED}✗${NC} .env file not found. Please create it first."
    exit 1
fi

# Function to run SQL script
run_sql_script() {
    local script_file=$1
    local description=$2
    
    echo ""
    echo -e "${YELLOW}Running:${NC} $description"
    echo "Script: $script_file"
    echo ""
    
    # Replace YourDatabaseName with actual database from .env
    sed "s/YourDatabaseName/$MSSQL_DATABASE/g" "$script_file" | \
    docker exec -i mssql-source /opt/mssql-tools18/bin/sqlcmd \
        -S localhost \
        -U "$MSSQL_USER" \
        -P "$MSSQL_PASSWORD" \
        -C
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓${NC} Success"
    else
        echo -e "${RED}✗${NC} Failed"
        return 1
    fi
}

# Menu
echo "Select an option:"
echo "1) Enable CDC on all existing tables"
echo "2) Set up auto-enable CDC trigger for new tables"
echo "3) Both (enable all + auto-enable trigger)"
echo "4) Check CDC status"
echo ""
read -p "Enter choice [1-4]: " choice

case $choice in
    1)
        run_sql_script "scripts/enable-cdc-all-tables.sql" "Enable CDC on all existing tables"
        ;;
    2)
        run_sql_script "scripts/auto-enable-cdc-trigger.sql" "Set up auto-enable CDC trigger"
        ;;
    3)
        run_sql_script "scripts/enable-cdc-all-tables.sql" "Enable CDC on all existing tables"
        echo ""
        run_sql_script "scripts/auto-enable-cdc-trigger.sql" "Set up auto-enable CDC trigger"
        ;;
    4)
        echo ""
        echo "Checking CDC status..."
        docker exec -i mssql-source /opt/mssql-tools18/bin/sqlcmd \
            -S localhost \
            -U "$MSSQL_USER" \
            -P "$MSSQL_PASSWORD" \
            -C \
            -d "$MSSQL_DATABASE" \
            -Q "SELECT s.name AS SchemaName, t.name AS TableName, t.is_tracked_by_cdc AS CDC_Enabled FROM sys.tables t INNER JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE t.type = 'U' ORDER BY s.name, t.name;"
        ;;
    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

echo ""
echo "========================================================="
echo -e "${GREEN}Done!${NC}"
echo "========================================================="
