# Final Deployment Guide - MS SQL to PostgreSQL CDC

Complete end-to-end deployment guide with centralized configuration.

---

## 📋 Prerequisites

### System Requirements

**Required Software:**
- Docker Engine 20.10+ & Docker Compose
- Python 3.8+
- Maven 3.6+ (for building custom transforms)
- jq (JSON processor)
- Git

**System Resources:**
- RAM: 8GB minimum (16GB recommended)
- CPU: 4+ cores
- Disk: 10GB free space

### Installation Commands

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install -y docker.io docker-compose python3 python3-pip maven jq git
sudo systemctl start docker
sudo usermod -aG docker $USER  # Logout and login after this
```

**MacOS:**
```bash
brew install docker docker-compose python maven jq git
```

**Windows:**
- Install Docker Desktop from docker.com
- Install Python from python.org
- Install Maven from maven.apache.org
- Install Git from git-scm.com

---

## 🗄️ Database Prerequisites

### MS SQL Server Requirements

**Your MS SQL database must have:**

1. **Database Created:**
   ```sql
   CREATE DATABASE YourDatabaseName;
   ```

2. **CDC Enabled on Database:**
   ```sql
   USE YourDatabaseName;
   EXEC sys.sp_cdc_enable_db;
   ```

3. **CDC Enabled on Tables:**
   
   **Option A: Enable on specific table:**
   ```sql
   EXEC sys.sp_cdc_enable_table
       @source_schema = N'dbo',
       @source_name = N'YourTableName',
       @role_name = NULL,
       @supports_net_changes = 1;
   ```
   
   **Option B: Enable on ALL existing tables (Automated):**
   ```bash
   # Run the automated script
   bash scripts/manage-cdc.sh
   # Select option 1: Enable CDC on all existing tables
   ```
   
   **Option C: Auto-enable CDC on new tables (Set up once):**
   ```bash
   # Run the automated script
   bash scripts/manage-cdc.sh
   # Select option 2: Set up auto-enable CDC trigger
   ```
   This creates a DDL trigger that automatically enables CDC whenever a new table is created.

4. **SQL Server Agent Running** (required for CDC cleanup)

**Verify CDC is enabled:**
```sql
-- Check database CDC status
SELECT name, is_cdc_enabled FROM sys.databases WHERE name = 'YourDatabaseName';

-- Check table CDC status
SELECT name, is_tracked_by_cdc FROM sys.tables WHERE is_tracked_by_cdc = 1;
```

### PostgreSQL Requirements

**Target database should exist** (schema will be auto-created):
```sql
CREATE DATABASE target_db;
```

---

## 🚀 Deployment Steps

### Step 1: Clone Repository

```bash
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git
cd mysql-cdc-go-single-table/debezium-setup
```

### Step 2: Configure Environment Variables

**Create `.env` file from template:**
```bash
cp .env.example .env
nano .env  # or use your preferred editor
```

**Update the following in `.env`:**

```bash
# ==============================================
# MS SQL Server Source Configuration
# ==============================================
MSSQL_HOST=host.docker.internal    # Change if MS SQL is remote
MSSQL_PORT=1433
MSSQL_USER=sa                      # Your MS SQL username
MSSQL_PASSWORD=YourPassword        # Your MS SQL password
MSSQL_DATABASE=YourDatabaseName    # Your source database name

# ==============================================
# PostgreSQL Target Configuration
# ==============================================
POSTGRES_HOST=host.docker.internal  # Change if PostgreSQL is remote
POSTGRES_PORT=5432
POSTGRES_USER=postgres              # Your PostgreSQL username
POSTGRES_PASSWORD=postgres          # Your PostgreSQL password
POSTGRES_DATABASE=target_db         # Your target database name
POSTGRES_SCHEMA=dbo

# ==============================================
# Kafka & Debezium Configuration
# ==============================================
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
TOPIC_PREFIX=mssql
SOURCE_CONNECTOR_NAME=mssql-source-connector
SINK_CONNECTOR_NAME=postgres-sink-connector
CONNECTOR_TASKS_MAX=10

# ==============================================
# Data Type Transformations
# ==============================================
# Comma-separated list of columns to convert to UUID
UUID_COLUMNS=user_id,customer_guid,product_guid

# Comma-separated list of columns to convert to JSON
JSON_COLUMNS=preferences_json,order_metadata

# ==============================================
# Performance Settings
# ==============================================
KAFKA_HEAP_OPTS="-Xms2G -Xmx4G"
CONNECT_TASK_SHUTDOWN_GRACEFUL_TIMEOUT_MS=30000
CONNECT_OFFSET_FLUSH_INTERVAL_MS=60000
```

**Important Configuration Notes:**

**For `MSSQL_HOST` and `POSTGRES_HOST`, use:**
- `host.docker.internal` - If databases are on your local machine (default)
- `172.17.0.1` - Alternative Docker bridge IP
- `192.168.1.100` - If database is on your local network
- `mssql.example.com` - If using remote hostname
- `database-1.abc.us-east-1.rds.amazonaws.com` - For AWS RDS
- `myserver.database.windows.net` - For Azure SQL

### Step 3: Deploy Everything

**Run the automated deployment script:**
```bash
bash scripts/deploy-all.sh
```

**What this script does:**
1. ✅ Loads configuration from `.env`
2. ✅ Validates prerequisites (Docker, Python, Maven, jq)
3. ✅ Starts infrastructure containers (Zookeeper, Kafka, Debezium, Kafka UI)
4. ✅ Validates MS SQL database and CDC setup
5. ✅ Cleans PostgreSQL target database
6. ✅ Replicates schema automatically from MS SQL to PostgreSQL
7. ✅ Builds and deploys custom transformations (snake_case, UUID, JSON)
8. ✅ Generates connector configurations from `.env`
9. ✅ Deploys source and sink connectors
10. ✅ Verifies data replication

**Expected output:**
```
✅ Infrastructure: Running
✅ MS SQL Prerequisites: Validated
✅ PostgreSQL Target: Cleaned and Schema Replicated
✅ Custom Transform: Deployed
✅ CDC Connectors: Running

🎉 Deployment completed successfully!
```

### Step 4: Verify Deployment

**Check connector status:**
```bash
curl http://localhost:8083/connectors | jq
```

**Expected output:**
```json
[
  "mssql-source-connector",
  "postgres-sink-connector"
]
```

**Check data replication:**
```bash
# Check PostgreSQL tables
docker exec -i postgres-target psql -U postgres -d target_db -c "\dt dbo.*"

# Check record count
docker exec -i postgres-target psql -U postgres -d target_db -c "SELECT COUNT(*) FROM dbo.your_table_name;"
```

---

## 🔄 What Happens Automatically

### 1. Schema Replication
- ✅ Reads all CDC-enabled tables from MS SQL
- ✅ Creates matching tables in PostgreSQL
- ✅ Preserves column types, lengths, and constraints
- ✅ Maps types: UNIQUEIDENTIFIER → UUID, BIT → BOOLEAN, VARCHAR(n) → VARCHAR(n)
- ✅ Converts CamelCase to snake_case

**Example:**
```sql
-- MS SQL (Source)
CREATE TABLE Employees (
    EmployeeID INT PRIMARY KEY,
    FirstName VARCHAR(50),
    CustomerGUID UNIQUEIDENTIFIER
);

-- PostgreSQL (Auto-created)
CREATE TABLE dbo.employees (
    employee_id INTEGER PRIMARY KEY,
    first_name VARCHAR(50),
    customer_guid UUID
);
```

### 2. Data Transformation
- ✅ **Snake_case conversion**: `FirstName` → `first_name`, `EmployeeID` → `employee_id`
- ✅ **UUID conversion**: String UUIDs → Native PostgreSQL UUID type
- ✅ **JSON conversion**: String JSON → Native PostgreSQL JSONB type
- ✅ **Soft delete tracking**: Deleted records marked with `__cdc_deleted=true`

### 3. Real-time CDC
- ✅ Initial snapshot of all existing data
- ✅ Real-time capture of INSERT, UPDATE, DELETE operations
- ✅ 5-10 second replication latency
- ✅ Exactly-once delivery semantics

---

## 🎯 Infrastructure Components

**All started automatically by `deploy-all.sh`:**

| Component | Port | Purpose |
|-----------|------|---------|
| Zookeeper | 2181 | Kafka coordination |
| Kafka | 9092 | Message broker for CDC events |
| Debezium Connect | 8083 | CDC connector runtime |
| Kafka UI | 8090 | Web UI for monitoring (http://localhost:8090) |
| MS SQL Source | 1433 | Your source database |
| PostgreSQL Target | 5432 | Your target database |

**Access Kafka UI:**
```
http://localhost:8090
```

---

## 🔍 Monitoring & Troubleshooting

### Check Connector Status

**Via REST API:**
```bash
# List all connectors
curl http://localhost:8083/connectors | jq

# Check source connector status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq

# Check sink connector status
curl http://localhost:8083/connectors/postgres-sink-connector/status | jq
```

**Expected healthy status:**
```json
{
  "name": "mssql-source-connector",
  "connector": {
    "state": "RUNNING"
  },
  "tasks": [
    {
      "id": 0,
      "state": "RUNNING"
    }
  ]
}
```

### View Logs

**Debezium Connect logs:**
```bash
docker logs -f debezium-connect
```

**Kafka logs:**
```bash
docker logs -f kafka
```

**Filter for errors:**
```bash
docker logs debezium-connect 2>&1 | grep -i error
```

### Monitor Kafka Topics

**List topics:**
```bash
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092
```

**View messages in topic:**
```bash
docker exec kafka kafka-console-consumer \
  --topic mssql.YourDatabase.dbo.YourTable \
  --from-beginning \
  --max-messages 5 \
  --bootstrap-server localhost:9092
```

### Verify Data in PostgreSQL

**Connect to PostgreSQL:**
```bash
docker exec -it postgres-target psql -U postgres -d target_db
```

**Check tables:**
```sql
\dt dbo.*
```

**Query data:**
```sql
SELECT * FROM dbo.employees LIMIT 10;
SELECT COUNT(*) FROM dbo.employees;
```

**Check for deleted records:**
```sql
SELECT * FROM dbo.employees WHERE __cdc_deleted = true;
```

---

## 🔧 Common Issues & Solutions

### Issue 1: Connector fails with "host.docker.internal not found"

**Solution:** Update `.env` file:
```bash
# Change from:
MSSQL_HOST=host.docker.internal

# To Docker bridge IP:
MSSQL_HOST=172.17.0.1

# Or to actual IP address:
MSSQL_HOST=192.168.1.100
```

Then redeploy:
```bash
docker compose down -v
bash scripts/deploy-all.sh
```

### Issue 2: "CDC is not enabled on database"

**Solution:** Enable CDC on your MS SQL database:
```sql
USE YourDatabaseName;
EXEC sys.sp_cdc_enable_db;

-- Enable on tables
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name = N'YourTableName',
    @role_name = NULL,
    @supports_net_changes = 1;
```

### Issue 3: No data appearing in PostgreSQL

**Check:**
1. Verify source connector is running: `curl http://localhost:8083/connectors/mssql-source-connector/status | jq`
2. Check Kafka topics exist: `docker exec kafka kafka-topics --list --bootstrap-server localhost:9092`
3. View connector logs: `docker logs debezium-connect 2>&1 | grep -i error`
4. Verify CDC is capturing changes: `SELECT * FROM cdc.dbo_YourTable_CT` (in MS SQL)

### Issue 4: "Connection refused" errors

**Verify databases are accessible:**
```bash
# Test MS SQL connection
docker exec mssql-source /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourPassword' -C -Q "SELECT @@VERSION"

# Test PostgreSQL connection
docker exec postgres-target psql -U postgres -d target_db -c "SELECT version();"
```

---

## 🔧 CDC Management for Source Database

### Automated CDC Management Script

**Use the helper script to manage CDC on all tables:**

```bash
bash scripts/manage-cdc.sh
```

**Available options:**

1. **Enable CDC on all existing tables** - Loops through all tables and enables CDC
2. **Set up auto-enable CDC trigger** - Creates a DDL trigger to auto-enable CDC on new tables
3. **Both** - Enables CDC on all existing + sets up auto-trigger for new tables
4. **Check CDC status** - Shows which tables have CDC enabled

### Manual CDC Management

**Enable CDC on all existing tables manually:**
```sql
-- Run in your MS SQL database
USE YourDatabaseName;
GO

-- Enable on all tables
DECLARE @schema NVARCHAR(128), @table NVARCHAR(128);
DECLARE cdc_cursor CURSOR FOR
    SELECT s.name, t.name 
    FROM sys.tables t 
    INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
    WHERE t.is_tracked_by_cdc = 0 AND t.type = 'U';

OPEN cdc_cursor;
FETCH NEXT FROM cdc_cursor INTO @schema, @table;

WHILE @@FETCH_STATUS = 0
BEGIN
    EXEC sys.sp_cdc_enable_table
        @source_schema = @schema,
        @source_name = @table,
        @role_name = NULL,
        @supports_net_changes = 1;
    FETCH NEXT FROM cdc_cursor INTO @schema, @table;
END;

CLOSE cdc_cursor;
DEALLOCATE cdc_cursor;
```

### Auto-Enable CDC on New Tables

**To automatically enable CDC when new tables are created:**

```bash
# Run the script and select option 2
bash scripts/manage-cdc.sh
```

This creates a DDL trigger that:
- ✅ Fires whenever a new table is created
- ✅ Automatically enables CDC on the new table
- ✅ No manual intervention needed for new tables
- ✅ One-time setup

**Advantages:**
- No need to remember to enable CDC on new tables
- Works for dynamically created tables
- Ensures all tables are tracked

**To verify the trigger:**
```sql
SELECT name, type_desc, is_disabled 
FROM sys.triggers 
WHERE parent_class_desc = 'DATABASE' 
AND name = 'trg_AutoEnableCDC_OnTableCreate';
```

### Check CDC Status

**Check which tables have CDC enabled:**
```bash
bash scripts/manage-cdc.sh
# Select option 4
```

**Or manually in SQL:**
```sql
SELECT 
    s.name AS SchemaName,
    t.name AS TableName,
    t.is_tracked_by_cdc AS CDC_Enabled
FROM sys.tables t
INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
WHERE t.type = 'U'
ORDER BY t.is_tracked_by_cdc DESC, s.name, t.name;
```

---

## 🔄 Making Configuration Changes

### To Change Database Credentials

**1. Update `.env` file:**
```bash
nano .env
# Update MSSQL_PASSWORD, POSTGRES_PASSWORD, etc.
```

**2. Redeploy:**
```bash
docker compose down -v
bash scripts/deploy-all.sh
```

### To Add New Tables

**1. Enable CDC on new tables in MS SQL:**
```sql
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name = N'NewTableName',
    @role_name = NULL,
    @supports_net_changes = 1;
```

**2. Redeploy connectors:**
```bash
bash scripts/deploy-all.sh
```

### To Add UUID or JSON Columns

**1. Update `.env` file:**
```bash
# Add column names to the comma-separated lists
UUID_COLUMNS=user_id,customer_guid,product_guid,new_uuid_column
JSON_COLUMNS=preferences_json,order_metadata,new_json_column
```

**2. Regenerate and redeploy connectors:**
```bash
bash scripts/generate-connectors.sh
curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart
```

---

## 🛑 Stopping the System

**Stop all containers:**
```bash
docker compose down
```

**Stop and remove all data (clean slate):**
```bash
docker compose down -v
```

---

## 📊 Performance Tuning

### For High Volume (500+ tables or millions of records)

**1. Increase resources in `.env`:**
```bash
KAFKA_HEAP_OPTS="-Xms4G -Xmx8G"
CONNECTOR_TASKS_MAX=20
```

**2. Adjust Docker resources:**
- Allocate more CPU cores (8+)
- Increase RAM to 16GB+

**3. Optimize Kafka settings in `docker-compose.yml`:**
```yaml
environment:
  KAFKA_NUM_PARTITIONS: 10
  KAFKA_DEFAULT_REPLICATION_FACTOR: 1
```

---

## 📁 Project Structure

```
debezium-setup/
├── .env                    # ⚙️ Your configuration (DO NOT COMMIT)
├── .env.example            # 📋 Configuration template
├── docker-compose.yml      # 🐳 Infrastructure definition
├── scripts/
│   ├── deploy-all.sh       # 🚀 Main deployment script
│   ├── generate-connectors.sh  # Generates JSON from .env
│   └── replicate-schema.py     # Schema replication
├── connectors/             # 🔌 Auto-generated connector configs
└── custom-smt/             # 🔧 Custom transformations
```

---

## ✅ Success Criteria

**Deployment is successful when:**

1. ✅ All Docker containers are running
2. ✅ Both connectors show status "RUNNING"
3. ✅ Kafka topics are created (visible in Kafka UI)
4. ✅ PostgreSQL tables are created with correct schema
5. ✅ Data appears in PostgreSQL (matching source count)
6. ✅ Column names are in snake_case
7. ✅ Real-time changes replicate within 5-10 seconds

**Verify with:**
```bash
# Check containers
docker ps

# Check connectors
curl http://localhost:8083/connectors | jq

# Check data
docker exec -i postgres-target psql -U postgres -d target_db -c "SELECT COUNT(*) FROM dbo.your_table;"
```

---

## 🎓 Key Concepts

### Single Source of Truth
All configuration is in `.env` file. No hardcoded values anywhere.

### Automatic Code Generation
Connector JSON files are generated from `.env` each deployment. Never edit them manually.

### Idempotent Deployment
Running `deploy-all.sh` multiple times is safe - it cleans and recreates everything.

### Zero Downtime Updates
For config changes, the script handles connector restarts automatically.

---

## 📞 Support & Resources

**Documentation:**
- Complete details: `COMPLETE_INSTALLATION_GUIDE.md`
- Schema replication: `AUTOMATIC_SCHEMA_REPLICATION.md`
- File structure: `FILE_STRUCTURE.md`

**Useful Commands:**
```bash
# View all logs
docker compose logs -f

# Restart a specific connector
curl -X POST http://localhost:8083/connectors/mssql-source-connector/restart

# Delete a connector
curl -X DELETE http://localhost:8083/connectors/mssql-source-connector

# Check Debezium version
curl http://localhost:8083/ | jq
```

**Kafka UI Dashboard:**
```
http://localhost:8090
```

---

## 🎉 That's It!

**You now have a fully automated CDC pipeline that:**
- ✅ Replicates schema automatically
- ✅ Transforms data (snake_case, UUID, JSON)
- ✅ Runs in real-time with low latency
- ✅ Tracks deletions (soft delete)
- ✅ Scales to hundreds of tables
- ✅ Configured via single `.env` file

**To deploy again or update configuration:**
```bash
nano .env              # Update configuration
docker compose down -v # Clean slate
bash scripts/deploy-all.sh  # Deploy
```

---

**Last Updated:** December 11, 2025  
**Version:** 2.0 (Centralized Configuration)
