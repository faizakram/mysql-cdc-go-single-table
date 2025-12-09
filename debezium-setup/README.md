# Debezium Setup for MS SQL Server → PostgreSQL (500 Tables)

## Architecture

```
MS SQL Server (500 tables)
    ↓
Debezium SQL Server Connector (reads CDC)
    ↓
Kafka Topics (1 topic per table = 500 topics)
    ↓
PostgreSQL Sink Connector
    ↓
PostgreSQL (500 tables replicated)
```

## Prerequisites

### 1. Enable CDC on MS SQL Server

Run this on your MS SQL Server for **each database**:

```sql
-- Enable CDC on database
USE YourDatabaseName;
EXEC sys.sp_cdc_enable_db;

-- Verify CDC is enabled
SELECT name, is_cdc_enabled 
FROM sys.databases 
WHERE name = 'YourDatabaseName';
```

### 2. Enable CDC on all 500 tables

**Option A: Enable for all tables** (recommended for 500 tables)
```sql
-- Get script to enable CDC on all tables
SELECT 
    'EXEC sys.sp_cdc_enable_table 
    @source_schema = ''' + SCHEMA_NAME(schema_id) + ''',
    @source_name = ''' + name + ''',
    @role_name = NULL,
    @supports_net_changes = 1;'
FROM sys.tables
WHERE is_ms_shipped = 0  -- Exclude system tables
AND schema_id = SCHEMA_ID('dbo');  -- Adjust schema if needed
```

Copy output and execute to enable CDC on all tables.

**Option B: Enable specific tables**
```sql
EXEC sys.sp_cdc_enable_table
    @source_schema = 'dbo',
    @source_name = 'YourTableName',
    @role_name = NULL,
    @supports_net_changes = 1;
```

### 3. Create SQL Server login for Debezium

```sql
-- Create login
CREATE LOGIN debezium WITH PASSWORD = 'StrongPassword123!';

-- Create user in your database
USE YourDatabaseName;
CREATE USER debezium FOR LOGIN debezium;

-- Grant permissions
EXEC sp_addrolemember 'db_owner', 'debezium';  -- Full access
-- OR grant specific permissions:
-- GRANT SELECT ON SCHEMA::dbo TO debezium;
-- GRANT SELECT ON SCHEMA::cdc TO debezium;
-- GRANT EXECUTE ON SCHEMA::cdc TO debezium;
```

## Quick Start

### Step 1: Configure Database Credentials

Edit `connectors/mssql-source.json`:
```json
{
  "database.hostname": "your-mssql-host.com",
  "database.port": "1433",
  "database.user": "debezium",
  "database.password": "StrongPassword123!",
  "database.names": "YourDatabaseName"
}
```

Edit `connectors/postgres-sink.json`:
```json
{
  "connection.url": "jdbc:postgresql://your-postgres-host:5432/target_db",
  "connection.user": "postgres",
  "connection.password": "your-postgres-password"
}
```

### Step 2: Start Services

```bash
cd debezium-setup

# Make scripts executable
chmod +x *.sh

# Start everything
./setup.sh
```

This will:
1. Start Kafka + Zookeeper + Debezium
2. Deploy MS SQL source connector
3. Deploy PostgreSQL sink connector
4. Start replicating 500 tables

### Step 3: Monitor Progress

```bash
# Check connector status
./status.sh

# View logs
docker-compose logs -f debezium-connect

# Access Kafka UI
open http://localhost:8080
```

## Expected Timeline

### Initial Snapshot (Full Load)
- **500 tables** with average **15M rows each** = **7.5 billion rows**
- **Debezium performance**: ~100K-500K rows/second
- **Estimated time**: 4-20 hours (depends on network, hardware)

### After Initial Load
- **CDC**: Real-time replication (<1 second lag)
- **Changes**: INSERT/UPDATE/DELETE captured instantly

## Monitoring

### 1. Kafka UI (Best for visual monitoring)
```
http://localhost:8080
```
- See all 500 topics
- Message counts per topic
- Consumer lag

### 2. Debezium API
```bash
# List connectors
curl http://localhost:8083/connectors

# Check status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq

# Check tasks
curl http://localhost:8083/connectors/mssql-source-connector/tasks | jq
```

### 3. PostgreSQL Progress
```sql
-- Check table counts
SELECT 
    schemaname,
    tablename,
    n_live_tup as row_count
FROM pg_stat_user_tables
ORDER BY n_live_tup DESC;

-- Compare with source
SELECT COUNT(*) FROM source_table;  -- MS SQL
SELECT COUNT(*) FROM target_table;  -- PostgreSQL
```

## Troubleshooting

### MS SQL CDC Not Working
```sql
-- Check if CDC is enabled
SELECT name, is_cdc_enabled FROM sys.databases;

-- Check CDC jobs
EXEC sys.sp_cdc_help_jobs;

-- Check CDC tables
SELECT * FROM cdc.change_tables;
```

### Connector Fails
```bash
# View detailed errors
docker-compose logs debezium-connect | grep ERROR

# Restart connector
curl -X POST http://localhost:8083/connectors/mssql-source-connector/restart

# Delete and recreate
curl -X DELETE http://localhost:8083/connectors/mssql-source-connector
./setup.sh
```

### Slow Performance
```bash
# Increase Debezium memory
# Edit docker-compose.yml:
KAFKA_HEAP_OPTS: "-Xms4G -Xmx8G"

# Increase tasks (parallel processing)
# Edit connectors/*.json:
"tasks.max": "20"

# Restart
docker-compose restart debezium-connect
```

## Type Mappings (Automatic)

Debezium handles these automatically:

| MS SQL Server | PostgreSQL | Notes |
|--------------|------------|-------|
| NVARCHAR | VARCHAR | ✅ Auto |
| VARCHAR | VARCHAR | ✅ Auto |
| INT | INTEGER | ✅ Auto |
| BIGINT | BIGINT | ✅ Auto |
| DECIMAL | NUMERIC | ✅ Auto |
| DATETIME2 | TIMESTAMP | ✅ Auto |
| BIT | BOOLEAN | ✅ Auto |
| UNIQUEIDENTIFIER | UUID | ✅ Auto |
| VARBINARY | BYTEA | ✅ Auto |

## Cost Estimate

**Hardware Requirements** (for 500 tables):
- Kafka: 4GB RAM, 2 CPU cores, 50GB disk
- Debezium: 4GB RAM, 2 CPU cores
- Total: 8GB RAM, 4 cores, 50GB disk

**Cloud Costs** (if self-hosting):
- AWS EC2 t3.xlarge: ~$120/month
- AWS EBS 50GB: ~$5/month
- **Total**: ~$125/month (only during migration)

**Alternative**: Run on existing server (just needs Docker)

## Useful Commands

```bash
# Start services
docker-compose up -d

# Stop services
docker-compose down

# View logs
docker-compose logs -f

# Check status
./status.sh

# Restart connectors
curl -X POST http://localhost:8083/connectors/mssql-source-connector/restart
curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart

# Pause replication
curl -X PUT http://localhost:8083/connectors/mssql-source-connector/pause

# Resume replication
curl -X PUT http://localhost:8083/connectors/mssql-source-connector/resume

# Delete connector
curl -X DELETE http://localhost:8083/connectors/mssql-source-connector
```

## Next Steps After Setup

1. **Validate Data**: Compare row counts between MS SQL and PostgreSQL
2. **Test Application**: Point app to PostgreSQL, verify functionality
3. **Cutover Plan**: Schedule downtime for final sync
4. **Decommission**: Stop Debezium after successful migration

## Support

- Debezium Docs: https://debezium.io/documentation/
- MS SQL Connector: https://debezium.io/documentation/reference/connectors/sqlserver.html
- Community: https://debezium.zulipchat.com/
