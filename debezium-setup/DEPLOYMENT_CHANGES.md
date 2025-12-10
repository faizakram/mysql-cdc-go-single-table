# Deployment Changes - Source Side Removed

## Summary
The `deploy-all.sh` script has been updated to **NOT create or modify anything on the MS SQL source side**. It now only validates prerequisites and works with existing source databases.

## What Changed

### ❌ Removed (No longer creates on source):
- ✗ Database creation (`CREATE DATABASE mig_test_db`)
- ✗ Table creation (`CREATE TABLE dbo.Employees`)
- ✗ Sample data insertion (`INSERT INTO dbo.Employees`)
- ✗ CDC enablement (`EXEC sys.sp_cdc_enable_db`)
- ✗ Table CDC enablement (`EXEC sys.sp_cdc_enable_table`)

### ✅ Added (Validates existing source):
- ✓ Check if database `mig_test_db` exists
- ✓ Check if CDC is enabled on database
- ✓ List CDC-enabled tables
- ✓ Count CDC-enabled tables
- ✓ Display helpful error messages if prerequisites missing

### ✅ Updated (Target side):
- ✓ Automatically cleans PostgreSQL target (`DROP SCHEMA dbo CASCADE`)
- ✓ Creates fresh schema (`CREATE SCHEMA dbo`)
- ✓ Runs schema replication from source
- ✓ Deploys connectors
- ✓ Verifies replication dynamically (detects first CDC table)

## Prerequisites (Your Responsibility)

Before running `deploy-all.sh`, ensure on **MS SQL Source**:

1. **Database exists**:
   ```sql
   CREATE DATABASE mig_test_db;
   ```

2. **CDC enabled on database**:
   ```sql
   USE mig_test_db;
   EXEC sys.sp_cdc_enable_db;
   ```

3. **Tables exist with data**:
   ```sql
   CREATE TABLE dbo.YourTable (...);
   INSERT INTO dbo.YourTable VALUES (...);
   ```

4. **CDC enabled on tables**:
   ```sql
   EXEC sys.sp_cdc_enable_table 
       @source_schema = N'dbo',
       @source_name = N'YourTable',
       @role_name = NULL,
       @supports_net_changes = 0;
   ```

5. **Verify CDC enabled**:
   ```sql
   -- Check database CDC status
   SELECT name, is_cdc_enabled FROM sys.databases WHERE name = 'mig_test_db';
   
   -- Check table CDC status
   SELECT name, is_tracked_by_cdc FROM sys.tables WHERE is_tracked_by_cdc = 1;
   ```

## What deploy-all.sh Does Now

### Step 0: Pre-flight Checks
- Validates required tools (docker, python3, java, mvn, curl, jq)
- Checks Python virtual environment
- Installs Python packages (pyodbc, psycopg2-binary)

### Step 1: Start Infrastructure
- Starts Docker containers (Kafka, Zookeeper, Debezium, MS SQL, PostgreSQL)
- Waits for all services to be ready

### Step 2: Validate MS SQL Prerequisites ⚠️ NEW
- ✅ Checks if database `mig_test_db` exists
- ✅ Verifies CDC is enabled on database
- ✅ Counts CDC-enabled tables
- ✅ Lists CDC-enabled tables
- ❌ Exits with error if prerequisites missing

### Step 3: Prepare PostgreSQL Target
- 🧹 Cleans PostgreSQL target database (`DROP SCHEMA dbo CASCADE`)
- 🆕 Creates fresh schema (`CREATE SCHEMA dbo`)
- 🔄 Runs automatic schema replication from MS SQL

### Step 4: Build and Deploy Custom Transform
- Builds snake_case SMT JAR
- Deploys to Debezium Connect
- Restarts Debezium Connect

### Step 5: Deploy CDC Connectors
- Deletes existing connectors (if any)
- Cleans up old Kafka topics
- Deploys MS SQL source connector
- Deploys PostgreSQL sink connector

### Step 6: Verify Deployment
- Dynamically detects first CDC-enabled table
- Compares record counts (MS SQL vs PostgreSQL)
- Displays sample data from PostgreSQL
- Shows connector status
- Lists Kafka topics

## Error Messages You'll See

### If database doesn't exist:
```
[ERROR] Source database 'mig_test_db' does not exist!
[INFO] Please create the database on MS SQL Server first
```

### If CDC not enabled:
```
[ERROR] CDC is not enabled on database 'mig_test_db'!
[INFO] Please enable CDC manually using:
  USE mig_test_db; EXEC sys.sp_cdc_enable_db;
```

### If no CDC tables found:
```
[WARNING] No CDC-enabled tables found in database!
[INFO] Make sure to enable CDC on your tables using:
  EXEC sys.sp_cdc_enable_table @source_schema = N'dbo', @source_name = N'YourTable', @role_name = NULL;
```

## Running the Script

### Prerequisites Setup (Do Once):
```bash
# 1. Create database
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -Q "CREATE DATABASE mig_test_db;"

# 2. Enable CDC on database
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -Q "USE mig_test_db; EXEC sys.sp_cdc_enable_db;"

# 3. Create your tables and insert data
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -d mig_test_db -Q "
CREATE TABLE dbo.Employees (
    EmployeeID INT PRIMARY KEY IDENTITY(1,1),
    FirstName NVARCHAR(50),
    LastName NVARCHAR(50),
    Salary DECIMAL(10,2)
);
INSERT INTO dbo.Employees VALUES ('John', 'Doe', 75000);
"

# 4. Enable CDC on tables
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -d mig_test_db -Q "
EXEC sys.sp_cdc_enable_table 
    @source_schema = N'dbo',
    @source_name = N'Employees',
    @role_name = NULL;
"
```

### Run Deployment:
```bash
cd /path/to/debezium-setup
source .venv/bin/activate
./scripts/deploy-all.sh
```

### For Fresh Deployment (Clean Slate):
```bash
# Remove all Docker volumes and state
docker compose down -v

# Run deployment
./scripts/deploy-all.sh
```

## Verification

After successful deployment:

```bash
# Check connector status
curl http://localhost:8083/connectors | jq

# Check source connector
curl http://localhost:8083/connectors/mssql-source-connector/status | jq

# Check sink connector
curl http://localhost:8083/connectors/postgres-sink-connector/status | jq

# Check PostgreSQL tables
docker exec -i postgres18 psql -U admin -d target_db -c "\dt dbo.*"

# Check data in PostgreSQL
docker exec -i postgres18 psql -U admin -d target_db -c "SELECT * FROM dbo.employees LIMIT 5;"

# Check Kafka topics
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 | grep mssql

# View Debezium logs
docker logs debezium-connect | grep -i snapshot
```

## Troubleshooting

### Snapshot not starting:
```bash
# Check if old offset exists
docker logs debezium-connect 2>&1 | grep -i "previous offset"

# Solution: Reset everything
docker compose down -v
./scripts/deploy-all.sh
```

### Schema mismatch errors:
```bash
# Check sink connector task status
curl -s http://localhost:8083/connectors/postgres-sink-connector/status | jq '.tasks'

# Get error details
curl -s http://localhost:8083/connectors/postgres-sink-connector/status | jq '.tasks[0].trace'

# Solution: Fix schema and redeploy
docker compose down -v
# Fix your source table schema
./scripts/deploy-all.sh
```

### No data in PostgreSQL:
```bash
# 1. Check if data in Kafka
docker exec kafka kafka-console-consumer --topic mssql.mig_test_db.dbo.YourTable --from-beginning --max-messages 1 --bootstrap-server localhost:9092

# 2. Check sink connector status
curl http://localhost:8083/connectors/postgres-sink-connector/status | jq

# 3. Check for errors
docker logs debezium-connect | tail -50
```

## Key Benefits

✅ **No source modifications** - Safe to use with production databases  
✅ **Validates prerequisites** - Catches configuration issues early  
✅ **Clean target** - Always starts with fresh PostgreSQL schema  
✅ **Comprehensive logs** - Clear success/error messages  
✅ **Automatic detection** - Works with any CDC-enabled table  
✅ **Idempotent** - Can run multiple times safely  

## Migration from Old Script

If you were using the old script that created MS SQL objects:

1. **Keep your existing MS SQL database** - Don't delete anything
2. **Verify CDC is enabled** - Run validation queries above
3. **Run new deploy-all.sh** - It will validate and proceed
4. **Target side is cleaned automatically** - No manual cleanup needed

---

**Note**: This script focuses on **deployment automation**, not database setup. Your source database, tables, and CDC configuration are your responsibility. The script will validate everything and fail fast with helpful error messages if prerequisites are missing.
