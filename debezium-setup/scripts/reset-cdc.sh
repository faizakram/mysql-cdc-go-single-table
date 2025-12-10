#!/bin/bash

#################################################################
# Reset CDC Script - Forces Fresh Snapshot
#
# Use this when you need to force a fresh initial snapshot
# from MS SQL to PostgreSQL
#################################################################

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo ""
echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}CDC Reset - Force Fresh Snapshot${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

print_warning "This will delete connectors, topics, and offsets to force a fresh snapshot!"
print_warning "Data in PostgreSQL will NOT be deleted, only connector state will be reset."
echo ""
read -p "Continue? (y/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    print_info "Cancelled."
    exit 0
fi

# Step 1: Delete connectors
print_info "Step 1: Deleting connectors..."
if curl -s http://localhost:8083/connectors | grep -q "mssql-source-connector"; then
    curl -X DELETE http://localhost:8083/connectors/mssql-source-connector 2>/dev/null
    print_success "mssql-source-connector deleted"
else
    print_info "mssql-source-connector not found"
fi

if curl -s http://localhost:8083/connectors | grep -q "postgres-sink-connector"; then
    curl -X DELETE http://localhost:8083/connectors/postgres-sink-connector 2>/dev/null
    print_success "postgres-sink-connector deleted"
else
    print_info "postgres-sink-connector not found"
fi

sleep 3

# Step 2: Delete Kafka topics (data and offsets)
print_info "Step 2: Deleting Kafka topics..."

# Get all relevant topics
TOPICS=$(docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null | grep -E "^mssql|schema-changes.mssql" || true)

if [ ! -z "$TOPICS" ]; then
    for topic in $TOPICS; do
        print_info "Deleting topic: $topic"
        docker exec kafka kafka-topics --delete --topic "$topic" --bootstrap-server localhost:9092 2>/dev/null || true
    done
    print_success "Topics deleted"
else
    print_info "No mssql topics found"
fi

sleep 3

# Step 3: Delete connector offsets (this is the key!)
print_info "Step 3: Deleting connector offsets and internal topics..."
print_warning "Stopping Debezium Connect to clear offsets..."

docker stop debezium-connect
sleep 5

# Delete the offsets and internal topics
print_info "Deleting Kafka Connect internal topics..."
docker exec kafka kafka-topics --delete --topic connect-offsets --bootstrap-server localhost:9092 2>/dev/null || true
docker exec kafka kafka-topics --delete --topic connect-configs --bootstrap-server localhost:9092 2>/dev/null || true  
docker exec kafka kafka-topics --delete --topic connect-status --bootstrap-server localhost:9092 2>/dev/null || true

# Also delete schema history topic if it exists
docker exec kafka kafka-topics --delete --topic schema-changes.mssql --bootstrap-server localhost:9092 2>/dev/null || true

sleep 3

print_info "Starting Debezium Connect..."
docker start debezium-connect

print_info "Waiting for Debezium Connect to be ready (60 seconds)..."
sleep 30

# Wait for Debezium Connect
for i in {1..30}; do
    if curl -s http://localhost:8083/ 2>/dev/null | grep -q "version"; then
        print_success "Debezium Connect is ready!"
        break
    fi
    echo -n "."
    sleep 2
done

echo ""

# Step 4: Redeploy connectors
print_info "Step 4: Redeploying connectors..."

print_info "Deploying MS SQL source connector (will do fresh snapshot)..."
curl -X POST -H "Content-Type: application/json" --data @connectors/mssql-source.json http://localhost:8083/connectors 2>/dev/null | jq -r '.name' || print_error "Failed to deploy source connector"
sleep 5

print_info "Deploying PostgreSQL sink connector..."
curl -X POST -H "Content-Type: application/json" --data @connectors/postgres-sink.json http://localhost:8083/connectors 2>/dev/null | jq -r '.name' || print_error "Failed to deploy sink connector"
sleep 5

# Step 5: Verify
print_info "Step 5: Waiting for snapshot to complete (30 seconds)..."
sleep 30

echo ""
print_info "Checking data replication..."

MSSQL_COUNT=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C -h -1 -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM dbo.Employees;" 2>/dev/null | tr -d ' ')
PG_COUNT=$(docker exec -i postgres18 psql -U admin -d target_db -t -c "SELECT COUNT(*) FROM dbo.employees;" 2>/dev/null | tr -d ' ')

echo ""
echo "📊 Data Counts:"
echo "  MS SQL Employees:        $MSSQL_COUNT"
echo "  PostgreSQL employees:    $PG_COUNT"
echo ""

if [ "$PG_COUNT" -gt 0 ]; then
    print_success "✅ Snapshot completed successfully!"
    echo ""
    print_info "Sample data from PostgreSQL:"
    docker exec -i postgres18 psql -U admin -d target_db -c "SELECT employee_id, first_name, last_name, salary FROM dbo.employees LIMIT 5;"
    echo ""
    print_success "🎉 CDC system is working!"
else
    print_warning "⚠️  Data not yet replicated"
    echo ""
    print_info "Checking snapshot status..."
    docker logs debezium-connect 2>&1 | grep -i "snapshot" | tail -5
    echo ""
    print_info "Check connector status:"
    echo "  curl http://localhost:8083/connectors/mssql-source-connector/status | jq"
fi

echo ""
print_info "To test real-time CDC:"
echo "  docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C -Q \"INSERT INTO dbo.Employees (FirstName, LastName, Salary) VALUES ('TestUser', 'ResetTest', 88000.00);\""
echo "  sleep 10"
echo "  docker exec -i postgres18 psql -U admin -d target_db -c \"SELECT * FROM dbo.employees WHERE first_name = 'TestUser';\""
echo ""
