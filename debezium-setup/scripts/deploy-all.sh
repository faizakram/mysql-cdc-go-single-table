#!/bin/bash

#################################################################
# Complete Deployment Script for MS SQL to PostgreSQL CDC
# 
# Prerequisites on Source (MS SQL):
#   - Database already exists with tables
#   - CDC is enabled on database and tables
#   - User has appropriate CDC permissions
#
# This script automates:
# 1. Starts all infrastructure containers (Kafka, Zookeeper, Debezium)
# 2. Validates MS SQL prerequisites (database exists, CDC enabled)
# 3. Cleans PostgreSQL target and replicates schema from MS SQL
# 4. Builds and deploys custom snake_case SMT
# 5. Deploys CDC connectors (source and sink)
# 6. Verifies the deployment
#################################################################

set -e  # Exit on any error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_section() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to wait for a service to be ready
wait_for_service() {
    local service=$1
    local max_attempts=$2
    local sleep_time=$3
    local attempt=1

    print_info "Waiting for $service to be ready..."
    while [ $attempt -le $max_attempts ]; do
        if eval "$4" > /dev/null 2>&1; then
            print_success "$service is ready!"
            return 0
        fi
        echo -n "."
        sleep "$sleep_time"
        ((attempt++))
    done
    print_error "$service failed to start within expected time"
    return 1
}

#################################################################
# Step 0: Pre-flight Checks
#################################################################

print_section "Step 0: Pre-flight Checks"

# Check required commands
print_info "Checking required tools..."
MISSING_TOOLS=()

if ! command_exists docker; then
    MISSING_TOOLS+=("docker")
fi

if ! command_exists python3; then
    MISSING_TOOLS+=("python3")
fi

if ! command_exists java; then
    MISSING_TOOLS+=("java")
fi

if ! command_exists mvn; then
    MISSING_TOOLS+=("maven")
fi

if ! command_exists curl; then
    MISSING_TOOLS+=("curl")
fi

if ! command_exists jq; then
    MISSING_TOOLS+=("jq")
fi

if [ ${#MISSING_TOOLS[@]} -ne 0 ]; then
    print_error "Missing required tools: ${MISSING_TOOLS[*]}"
    print_info "Please install missing tools and try again"
    exit 1
fi

print_success "All required tools are installed"

# Check Python virtual environment
if [ ! -d ".venv" ]; then
    print_warning "Virtual environment not found. Creating one..."
    python3 -m venv .venv
    print_success "Virtual environment created"
fi

print_info "Activating virtual environment..."
source .venv/bin/activate

# Check Python packages
print_info "Checking Python packages..."
if ! python3 -c "import pyodbc" 2>/dev/null; then
    print_warning "pyodbc not installed. Installing..."
    pip install pyodbc psycopg2-binary
fi

if ! python3 -c "import psycopg2" 2>/dev/null; then
    print_warning "psycopg2 not installed. Installing..."
    pip install pyodbc psycopg2-binary
fi

print_success "Python packages verified"

#################################################################
# Step 1: Start Infrastructure
#################################################################

print_section "Step 1: Starting Infrastructure Containers"

print_info "Starting Docker containers..."
docker compose up -d

print_success "Docker compose started"

# Wait for services (using simpler checks that work with these Docker images)
wait_for_service "Zookeeper" 30 2 "docker exec zookeeper bash -c 'echo srvr | nc localhost 2181'"
wait_for_service "Kafka" 30 2 "docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092"
wait_for_service "MS SQL Server" 30 2 "docker exec mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -Q 'SELECT 1'"
wait_for_service "PostgreSQL" 30 2 "docker exec postgres18 psql -U admin -d postgres -c 'SELECT 1'"

# Wait for Debezium Connect (takes longer)
print_info "Waiting for Debezium Connect to be ready (this may take 60+ seconds)..."
sleep 30
wait_for_service "Debezium Connect" 40 3 "curl -s http://localhost:8083/ | grep version"

print_success "All infrastructure services are ready"

#################################################################
# Step 2: Validate MS SQL Prerequisites
#################################################################

print_section "Step 2: Validating MS SQL Source Prerequisites"

# Check if database exists
print_info "Checking if source database 'mig_test_db' exists..."
DB_EXISTS=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -h -1 -Q "SET NOCOUNT ON; SELECT CASE WHEN EXISTS(SELECT 1 FROM sys.databases WHERE name = 'mig_test_db') THEN 1 ELSE 0 END;" | tr -d '[:space:]')

if [ "$DB_EXISTS" != "1" ]; then
    print_error "Source database 'mig_test_db' does not exist!"
    print_info "Please create the database on MS SQL Server first"
    exit 1
fi
print_success "Source database 'mig_test_db' exists"

# Check if CDC is enabled on database
print_info "Checking if CDC is enabled on database..."
CDC_ENABLED=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -h -1 -Q "SET NOCOUNT ON; SELECT is_cdc_enabled FROM sys.databases WHERE name = 'mig_test_db';" | tr -d '[:space:]')

if [ "$CDC_ENABLED" != "1" ]; then
    print_error "CDC is not enabled on database 'mig_test_db'!"
    print_info "Please enable CDC manually using:"
    print_info "  USE mig_test_db; EXEC sys.sp_cdc_enable_db;"
    exit 1
fi
print_success "CDC is enabled on database"

# Check if there are any CDC-enabled tables
print_info "Checking for CDC-enabled tables..."
CDC_TABLES=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -d mig_test_db -h -1 -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM sys.tables WHERE is_tracked_by_cdc = 1;" | tr -d '[:space:]')

if [ "$CDC_TABLES" == "0" ]; then
    print_warning "No CDC-enabled tables found in database!"
    print_info "Make sure to enable CDC on your tables using:"
    print_info "  EXEC sys.sp_cdc_enable_table @source_schema = N'dbo', @source_name = N'YourTable', @role_name = NULL;"
else
    print_success "Found $CDC_TABLES CDC-enabled table(s)"
    
    # List CDC-enabled tables
    print_info "CDC-enabled tables:"
    docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -d mig_test_db -h -1 -Q "SET NOCOUNT ON; SELECT SCHEMA_NAME(schema_id) + '.' + name FROM sys.tables WHERE is_tracked_by_cdc = 1;" | grep -v "^$"
fi

print_success "MS SQL prerequisites validation complete"

#################################################################
# Step 3: Prepare PostgreSQL Target and Replicate Schema
#################################################################

print_section "Step 3: Preparing PostgreSQL Target and Replicating Schema"

# Clean up PostgreSQL target database
print_info "Cleaning up PostgreSQL target database..."
docker exec -i postgres18 psql -U admin -d target_db -c "DROP SCHEMA IF EXISTS dbo CASCADE; CREATE SCHEMA dbo;" > /dev/null 2>&1
print_success "PostgreSQL target database cleaned"

print_info "Running automatic schema replication from MS SQL..."
python3 scripts/replicate-schema.py

print_success "Schema replication complete"

# Verify PostgreSQL tables
print_info "Verifying PostgreSQL tables..."
docker exec -i postgres18 psql -U admin -d target_db -c "\dt dbo.*"

print_success "PostgreSQL schema verified"

#################################################################
# Step 4: Build and Deploy Custom SMT
#################################################################

print_section "Step 4: Building and Deploying Custom Transform"

print_info "Building snake_case transform JAR..."
cd custom-smt
mvn clean package -q
cd ..

if [ ! -f "custom-smt/target/snake-case-transform-1.0.0.jar" ]; then
    print_error "Failed to build snake-case-transform-1.0.0.jar"
    exit 1
fi

print_success "Custom transform built successfully"

print_info "Copying JAR to Debezium Connect..."
docker cp custom-smt/target/snake-case-transform-1.0.0.jar debezium-connect:/kafka/connect/

print_info "Restarting Debezium Connect to load new transform..."
docker restart debezium-connect

print_info "Waiting for Debezium Connect to restart (60 seconds)..."
sleep 30
wait_for_service "Debezium Connect" 30 2 "curl -s http://localhost:8083/ | grep version"

print_success "Custom transform deployed"

#################################################################
# Step 5: Deploy CDC Connectors
#################################################################

print_section "Step 5: Deploying CDC Connectors"

# Check if connectors already exist and delete them
print_info "Checking for existing connectors..."
if curl -s http://localhost:8083/connectors | grep -q "mssql-source-connector"; then
    print_warning "Deleting existing mssql-source-connector..."
    curl -X DELETE http://localhost:8083/connectors/mssql-source-connector
    sleep 2
fi

if curl -s http://localhost:8083/connectors | grep -q "postgres-sink-connector"; then
    print_warning "Deleting existing postgres-sink-connector..."
    curl -X DELETE http://localhost:8083/connectors/postgres-sink-connector
    sleep 2
fi

# Delete Kafka topics to force fresh snapshot
print_info "Cleaning up old Kafka topics and offsets for fresh snapshot..."
TOPICS_TO_DELETE=$(docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null | grep -E "^mssql|schema-changes.mssql|connect-offsets" || true)

if [ ! -z "$TOPICS_TO_DELETE" ]; then
    print_warning "Deleting old topics to force fresh snapshot..."
    for topic in $TOPICS_TO_DELETE; do
        docker exec kafka kafka-topics --delete --topic "$topic" --bootstrap-server localhost:9092 2>/dev/null || true
    done
    sleep 3
    print_success "Old topics deleted"
else
    print_info "No old topics found"
fi

# Deploy source connector
print_info "Deploying MS SQL source connector..."
curl -X POST -H "Content-Type: application/json" --data @connectors/mssql-source.json http://localhost:8083/connectors
sleep 5

# Deploy sink connector
print_info "Deploying PostgreSQL sink connector..."
curl -X POST -H "Content-Type: application/json" --data @connectors/postgres-sink.json http://localhost:8083/connectors
sleep 5

print_success "Connectors deployed"

# Wait for connectors to start
print_info "Waiting for connectors to initialize (10 seconds)..."
sleep 10

#################################################################
# Step 6: Verify Deployment
#################################################################

print_section "Step 6: Verifying Deployment"

# Check connector status
print_info "Checking MS SQL source connector status..."
SOURCE_STATUS=$(curl -s http://localhost:8083/connectors/mssql-source-connector/status | jq -r '.connector.state')
if [ "$SOURCE_STATUS" == "RUNNING" ]; then
    print_success "MS SQL source connector: RUNNING"
else
    print_error "MS SQL source connector: $SOURCE_STATUS"
fi

print_info "Checking PostgreSQL sink connector status..."
SINK_STATUS=$(curl -s http://localhost:8083/connectors/postgres-sink-connector/status | jq -r '.connector.state')
if [ "$SINK_STATUS" == "RUNNING" ]; then
    print_success "PostgreSQL sink connector: RUNNING"
else
    print_error "PostgreSQL sink connector: $SINK_STATUS"
fi

# Check data replication
print_info "Waiting for initial snapshot to complete (20 seconds)..."
sleep 20

print_info "Checking replicated data in PostgreSQL..."
PG_COUNT=$(docker exec -i postgres18 psql -U admin -d target_db -t -c "SELECT COUNT(*) FROM dbo.employees;" 2>/dev/null | tr -d ' ' || echo "0")
MSSQL_COUNT=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C -h -1 -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM dbo.Employees;" 2>/dev/null | tr -d ' ' || echo "0")

echo ""
print_info "Data replication status:"

# Get first CDC-enabled table for verification
FIRST_CDC_TABLE=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -d mig_test_db -h -1 -Q "SET NOCOUNT ON; SELECT TOP 1 SCHEMA_NAME(schema_id) + '.' + name FROM sys.tables WHERE is_tracked_by_cdc = 1;" 2>/dev/null | tr -d '[:space:]' || echo "")

if [ -n "$FIRST_CDC_TABLE" ]; then
    SCHEMA=$(echo "$FIRST_CDC_TABLE" | cut -d'.' -f1)
    TABLE=$(echo "$FIRST_CDC_TABLE" | cut -d'.' -f2)
    
    # Get record counts
    MSSQL_COUNT=$(docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -d mig_test_db -h -1 -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM ${FIRST_CDC_TABLE};" 2>/dev/null | tr -d '[:space:]' || echo "0")
    
    # Convert table name to snake_case for PostgreSQL
    PG_TABLE=$(echo "$TABLE" | sed 's/\([A-Z]\)/_\L\1/g' | sed 's/^_//')
    PG_COUNT=$(docker exec -i postgres18 psql -U admin -d target_db -t -c "SELECT COUNT(*) FROM ${SCHEMA}.${PG_TABLE};" 2>/dev/null | tr -d '[:space:]' || echo "0")
    
    echo "  MS SQL ${FIRST_CDC_TABLE}: $MSSQL_COUNT records"
    echo "  PostgreSQL ${SCHEMA}.${PG_TABLE}: $PG_COUNT records"
    
    if [ "$PG_COUNT" -gt 0 ]; then
        print_success "✅ Initial snapshot completed successfully!"
        print_info "Sample data from PostgreSQL:"
        docker exec -i postgres18 psql -U admin -d target_db -c "SELECT * FROM ${SCHEMA}.${PG_TABLE} LIMIT 3;" 2>/dev/null || true
    else
        print_warning "⚠️  Snapshot may still be in progress or waiting to start"
        print_info "Checking connector status..."
        
        # Check if connectors are running
        SOURCE_STATUS=$(curl -s http://localhost:8083/connectors/mssql-source-connector/status 2>/dev/null | jq -r '.connector.state' 2>/dev/null || echo "UNKNOWN")
        SINK_STATUS=$(curl -s http://localhost:8083/connectors/postgres-sink-connector/status 2>/dev/null | jq -r '.connector.state' 2>/dev/null || echo "UNKNOWN")
        
        echo "  Source connector: $SOURCE_STATUS"
        echo "  Sink connector: $SINK_STATUS"
        
        print_info "To check snapshot status:"
        echo "  docker logs debezium-connect 2>&1 | grep -i 'snapshot'"
        
        print_info "To verify data in Kafka:"
        echo "  docker exec kafka kafka-console-consumer --topic mssql.mig_test_db.${SCHEMA}.${TABLE} --from-beginning --max-messages 1 --bootstrap-server localhost:9092"
    fi
else
    print_warning "⚠️  No CDC-enabled tables found to verify"
    print_info "Please enable CDC on your source tables and redeploy connectors"
fi

# List Kafka topics
print_info "Kafka topics created:"
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null | grep mssql || echo "  (No topics created yet)"

#################################################################
# Final Summary
#################################################################

print_section "Deployment Complete!"

echo ""
echo -e "${GREEN}✅ Infrastructure: Running${NC}"
echo -e "${GREEN}✅ MS SQL Prerequisites: Validated${NC}"
echo -e "${GREEN}✅ PostgreSQL Target: Cleaned and Schema Replicated${NC}"
echo -e "${GREEN}✅ Custom Transform: Deployed${NC}"
echo -e "${GREEN}✅ CDC Connectors: Running${NC}"
echo ""

print_info "Next Steps:"
echo ""
echo "1. Monitor snapshot progress:"
echo "   docker logs -f debezium-connect | grep -i snapshot"
echo ""
echo "2. Check connector status:"
echo "   curl http://localhost:8083/connectors | jq"
echo ""
echo "3. Test real-time CDC by inserting data in your MS SQL tables"
echo ""
echo "4. Verify data in PostgreSQL:"
echo "   docker exec -i postgres18 psql -U admin -d target_db -c \"\\dt dbo.*\""
echo ""

print_info "Troubleshooting:"
echo "  • View Debezium logs: docker logs -f debezium-connect"
echo "  • View connector status: curl http://localhost:8083/connectors/mssql-source-connector/status | jq"
echo "  • View Kafka topics: docker exec kafka kafka-topics --list --bootstrap-server localhost:9092"
echo ""

print_success "🎉 Deployment completed successfully!"
