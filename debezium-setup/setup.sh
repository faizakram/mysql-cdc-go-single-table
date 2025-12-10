#!/bin/bash

# Debezium Setup Script for MS SQL Server → PostgreSQL
# Handles 500 tables with CDC replication

set -e

DEBEZIUM_URL="http://localhost:8083"
CONNECTORS_DIR="./connectors"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║   Debezium Setup - MS SQL Server → PostgreSQL (500 tables) ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to wait for service
wait_for_service() {
    local url=$1
    local service_name=$2
    local max_attempts=60
    local attempt=0

    echo -e "${YELLOW}⏳ Waiting for $service_name to be ready...${NC}"
    
    while [ $attempt -lt $max_attempts ]; do
        if curl -s "$url" > /dev/null 2>&1; then
            echo -e "${GREEN}✓ $service_name is ready!${NC}"
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    
    echo -e "${RED}✗ $service_name failed to start after $max_attempts attempts${NC}"
    return 1
}

# Step 1: Start all services
echo -e "${YELLOW}Step 1: Starting Kafka, Zookeeper, and Debezium...${NC}"
docker compose up -d

# Step 2: Wait for services to be ready
wait_for_service "http://localhost:9092" "Kafka"
wait_for_service "$DEBEZIUM_URL" "Debezium Connect"
wait_for_service "http://localhost:8080" "Kafka UI"

echo ""
echo -e "${GREEN}✓ All services started successfully!${NC}"
echo ""

# Step 3: Check Debezium plugins
echo -e "${YELLOW}Step 2: Checking Debezium connectors...${NC}"
PLUGINS=$(curl -s $DEBEZIUM_URL/connector-plugins)
echo "$PLUGINS" | jq '.'

if echo "$PLUGINS" | grep -q "SqlServerConnector"; then
    echo -e "${GREEN}✓ MS SQL Server connector found${NC}"
else
    echo -e "${RED}✗ MS SQL Server connector not found!${NC}"
    exit 1
fi

echo ""

# Step 4: Configure and deploy MS SQL source connector
echo -e "${YELLOW}Step 3: Deploying MS SQL Server source connector...${NC}"
echo -e "${YELLOW}⚠ IMPORTANT: Edit connectors/mssql-source.json with your MS SQL credentials first!${NC}"
echo ""
read -p "Have you configured connectors/mssql-source.json? (yes/no): " configured

if [ "$configured" != "yes" ]; then
    echo -e "${YELLOW}Please edit the following files:${NC}"
    echo "  1. connectors/mssql-source.json - MS SQL connection details"
    echo "  2. connectors/postgres-sink.json - PostgreSQL connection details"
    echo ""
    echo "Then run: ./setup.sh"
    exit 0
fi

# Deploy MS SQL source connector
curl -i -X POST -H "Accept:application/json" -H "Content-Type:application/json" \
    $DEBEZIUM_URL/connectors/ -d @$CONNECTORS_DIR/mssql-source.json

echo ""
echo -e "${GREEN}✓ MS SQL source connector deployed${NC}"

# Wait a bit for connector to initialize
sleep 5

# Step 5: Deploy PostgreSQL sink connector
echo ""
echo -e "${YELLOW}Step 4: Deploying PostgreSQL sink connector...${NC}"

curl -i -X POST -H "Accept:application/json" -H "Content-Type:application/json" \
    $DEBEZIUM_URL/connectors/ -d @$CONNECTORS_DIR/postgres-sink.json

echo ""
echo -e "${GREEN}✓ PostgreSQL sink connector deployed${NC}"

# Step 6: Check connector status
echo ""
echo -e "${YELLOW}Step 5: Checking connector status...${NC}"
sleep 3

MSSQL_STATUS=$(curl -s $DEBEZIUM_URL/connectors/mssql-source-connector/status | jq '.connector.state')
PG_STATUS=$(curl -s $DEBEZIUM_URL/connectors/postgres-sink-connector/status | jq '.connector.state')

echo "MS SQL Connector: $MSSQL_STATUS"
echo "PostgreSQL Connector: $PG_STATUS"

# Step 7: Display monitoring URLs
echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                   Setup Complete! 🎉                        ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "Access these UIs to monitor:"
echo "  📊 Kafka UI:         http://localhost:8080"
echo "  🔌 Debezium API:     http://localhost:8083"
echo "  📈 Connector Status: http://localhost:8083/connectors"
echo ""
echo "Useful commands:"
echo "  ./status.sh          - Check connector status"
echo "  ./logs.sh            - View connector logs"
echo "  ./stop.sh            - Stop all services"
echo "  ./restart.sh         - Restart connectors"
echo ""
echo -e "${YELLOW}⏳ Now watching 500 tables... Changes will appear in PostgreSQL!${NC}"
