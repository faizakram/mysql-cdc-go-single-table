# Deploy-All Script Guide

## Overview

The `deploy-all.sh` script is a comprehensive automation tool that handles the entire deployment process for the MS SQL to PostgreSQL CDC system with **centralized configuration via `.env` file**. It eliminates manual steps and ensures consistent deployment across environments.

## What It Does

This script automates:

1. **Configuration Loading** - Loads all settings from `.env` file (single source of truth)
2. **Pre-flight Checks** - Verifies all required tools are installed
3. **Virtual Environment Setup** - Creates and activates Python venv with dependencies
4. **Infrastructure Startup** - Launches all Docker containers (Zookeeper, Kafka, Debezium, MS SQL, PostgreSQL)
5. **MS SQL Validation** - Verifies database exists, CDC enabled, tables configured
6. **Schema Replication** - Automatically replicates MS SQL schema to PostgreSQL
7. **Custom Transform Build** - Compiles and deploys snake_case transformation JAR
8. **Connector Generation** - Generates connector JSON files from `.env` configuration
9. **Connector Deployment** - Deploys both source and sink connectors
10. **Verification** - Confirms all components are running and data is replicating

## Prerequisites

### First Time Setup

**1. Configure Environment Variables**

```bash
cd /path/to/mysql-cdc-go-single-table/debezium-setup

# Copy the example configuration
cp .env.example .env

# Edit with your database credentials
nano .env  # or use your preferred editor
```

**Required settings in `.env`:**
- `MSSQL_DATABASE` - Your source database name
- `MSSQL_USER` / `MSSQL_PASSWORD` - MS SQL credentials
- `POSTGRES_DATABASE` - Target database name
- `POSTGRES_USER` / `POSTGRES_PASSWORD` - PostgreSQL credentials

**2. Ensure MS SQL is ready**
- Database must exist
- CDC must be enabled
- Tables must have CDC enabled

## Usage

### Basic Usage

```bash
# From the debezium-setup directory
bash scripts/deploy-all.sh
```

The script will:
1. Load configuration from `.env`
2. Validate all settings
3. Deploy complete CDC pipeline
4. Show verification results

```bash
# Clone the repository
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git
cd mysql-cdc-go-single-table/debezium-setup

# Make script executable (if needed)
chmod +x scripts/deploy-all.sh

# Run deployment
./scripts/deploy-all.sh
```

### Re-running After Changes

```bash
# The script is idempotent - safe to run multiple times
# It will:
# - Skip already created resources
# - Delete and recreate connectors
# - Update transforms if changed

./scripts/deploy-all.sh
```

## Expected Output

The script provides colored output for easy monitoring:

```
========================================
Step 0: Pre-flight Checks
========================================
[INFO] Checking required tools...
[SUCCESS] All required tools are installed
[INFO] Activating virtual environment...
[SUCCESS] Python packages verified

========================================
Step 1: Starting Infrastructure Containers
========================================
[INFO] Starting Docker containers...
[SUCCESS] Docker compose started
[INFO] Waiting for Zookeeper to be ready...
[SUCCESS] Zookeeper is ready!
...

========================================
Deployment Complete!
========================================

✅ Infrastructure: Running
✅ MS SQL Database: Configured with CDC
✅ PostgreSQL Schema: Replicated
✅ Custom Transform: Deployed
✅ CDC Connectors: Running
✅ Data Replication: Active

🎉 Deployment completed successfully!
```

## Prerequisites

The script will check for these tools:

- **docker** - For container management
- **python3** - For schema replication script
- **java** - For building custom transforms
- **mvn** (Maven) - For Java builds
- **curl** - For API calls
- **jq** - For JSON parsing

If any tool is missing, the script will exit with an error message.

## What Gets Created

After successful execution:

### Docker Containers
- `zookeeper` - Coordination service
- `kafka` - Message broker
- `debezium-connect` - CDC platform
- `mssql-test` - MS SQL Server 2019
- `postgres18` - PostgreSQL 18

### MS SQL Database
- Database: `mig_test_db`
- Table: `dbo.Employees` (with CDC enabled)
- Sample data: 3 employee records

### PostgreSQL Database
- Database: `target_db`
- Schema: `dbo`
- Table: `dbo.employees` (snake_case columns)

### CDC Connectors
- `mssql-source-connector` - Captures MS SQL changes
- `postgres-sink-connector` - Writes to PostgreSQL

## Troubleshooting

### Script Fails at Pre-flight Checks

**Problem:** Missing required tools

**Solution:**
```bash
# Ubuntu/Debian
sudo apt-get install docker.io python3 python3-venv openjdk-11-jdk maven curl jq

# macOS
brew install docker python openjdk@11 maven curl jq
```

### Script Hangs at "Waiting for Service"

**Problem:** Service taking longer than expected to start

**Solution:**
```bash
# Check logs manually
docker logs debezium-connect
docker logs mssql-test
docker logs postgres18

# Increase wait times by editing the script
# Change: wait_for_service "Service Name" 30 2 "command"
# To:     wait_for_service "Service Name" 60 3 "command"
```

### Connector Deployment Fails

**Problem:** Connectors showing FAILED state

**Solution:**
```bash
# Check connector status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq

# View detailed logs
docker logs debezium-connect | grep ERROR

# Manually restart connector
curl -X POST http://localhost:8083/connectors/mssql-source-connector/restart
```

### Python Package Installation Fails (Ubuntu 24.04+)

**Problem:** PEP 668 externally-managed-environment error

**Solution:** The script automatically creates a virtual environment in `.venv/` to avoid this issue. If it still fails:

```bash
# Manually create venv
python3 -m venv .venv
source .venv/bin/activate
pip install pyodbc psycopg2-binary

# Run script again
./scripts/deploy-all.sh
```

### Port Already in Use

**Problem:** Docker fails to start containers due to port conflicts

**Solution:**
```bash
# Check what's using the ports
sudo lsof -i :1433  # MS SQL
sudo lsof -i :5432  # PostgreSQL
sudo lsof -i :8083  # Debezium Connect

# Stop conflicting services or edit docker-compose.yml to use different ports
```

## Script Features

### Color-Coded Output
- 🔵 **BLUE [INFO]** - Informational messages
- 🟢 **GREEN [SUCCESS]** - Successful operations
- 🟡 **YELLOW [WARNING]** - Warnings (non-critical)
- 🔴 **RED [ERROR]** - Errors requiring attention

### Error Handling
- `set -e` - Script exits immediately on any error
- Service readiness checks with retries
- Connector status validation
- Data replication verification

### Idempotency
Safe to run multiple times:
- Checks if resources exist before creating
- Deletes and recreates connectors for fresh deployment
- Skips database creation if already exists
- Updates transforms without breaking existing setup

## Manual Steps After Deployment

After the script completes, you can:

### Test Real-Time CDC

```bash
# Insert data in MS SQL
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C -Q "
INSERT INTO dbo.Employees (FirstName, LastName, Salary) 
VALUES ('Alice', 'Wonder', 95000.00);
"

# Wait 10 seconds
sleep 10

# Check PostgreSQL
docker exec -i postgres18 psql -U admin -d target_db -c "
SELECT * FROM dbo.employees WHERE first_name = 'Alice';
"
```

### Monitor Connectors

```bash
# List all connectors
curl http://localhost:8083/connectors | jq

# Check specific connector status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq

# View connector configuration
curl http://localhost:8083/connectors/mssql-source-connector | jq
```

### View Kafka Topics

```bash
# List topics
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# Consume messages from a topic
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic mssql_test_db.dbo.Employees \
  --from-beginning \
  --max-messages 5
```

## Advanced Usage

### Custom Configuration

You can modify the script to use different:

**Database Names:**
```bash
# Edit line ~150 in deploy-all.sh
# Change: CREATE DATABASE mig_test_db
# To:     CREATE DATABASE your_database_name
```

**Table Names:**
```bash
# Edit line ~160 in deploy-all.sh
# Add your own CREATE TABLE statements
```

**Connector Settings:**
```bash
# Edit connector JSON files:
# connectors/mssql-source.json
# connectors/postgres-sink.json
```

### Running Specific Phases

You can comment out phases in the script to skip certain steps:

```bash
# Edit deploy-all.sh and comment out sections:
# Step 2: Setup MS SQL Database        # Skip if already set up
# Step 4: Build and Deploy Custom SMT  # Skip if transform unchanged
# Step 5: Deploy CDC Connectors        # Skip if connectors already running
```

## Integration with CI/CD

The script can be integrated into CI/CD pipelines:

```yaml
# GitHub Actions example
- name: Deploy CDC System
  run: |
    cd debezium-setup
    ./scripts/deploy-all.sh
  env:
    MSSQL_PASSWORD: ${{ secrets.MSSQL_PASSWORD }}
    POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
```

## Related Scripts

- `scripts/replicate-schema.py` - Python script for automatic schema replication
- `scripts/integration-test.sh` - End-to-end testing script
- `scripts/deploy-smt.sh` - Deploy custom transform only

## Support

For issues or questions:

1. Check [TROUBLESHOOTING.md](COMPLETE_INSTALLATION_GUIDE.md#troubleshooting)
2. Review script output for specific error messages
3. Check Docker logs: `docker logs debezium-connect`
4. Verify prerequisites are installed correctly

## Version History

- **v1.0** - Initial release with full automation
  - Pre-flight checks
  - Infrastructure setup
  - Schema replication
  - Connector deployment
  - Verification steps
