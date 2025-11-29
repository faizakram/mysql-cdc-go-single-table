# Quick Start - External Databases

## Step 1: Edit Configuration

Open `run-external-db.sh` and update these values:

```bash
# Source Database
SRC_HOST="your-source-hostname"     # e.g., "prod-mysql.company.com" or "192.168.1.10"
SRC_PORT="3306"
SRC_USER="your_username"
SRC_PASS="your_password"
SRC_DATABASE="your_db_name"
SRC_TABLE="your_table_name"

# Target Database
TGT_HOST="your-target-hostname"     # e.g., "replica-mysql.company.com" or "192.168.1.20"
TGT_PORT="3306"
TGT_USER="your_username"
TGT_PASS="your_password"
TGT_DATABASE="your_db_name"
TGT_TABLE="your_target_table"
```

## Step 2: Run CDC

```bash
./run-external-db.sh
```

That's it! The script will:
- ✅ Build Docker image (if needed)
- ✅ Start CDC container
- ✅ Perform full table load (initial sync)
- ✅ Start real-time CDC replication
- ✅ Show live logs

## Monitoring

```bash
# View logs
docker logs -f mysql-cdc

# Check if running
docker ps | grep mysql-cdc

# Check progress in target database
docker exec mysql-cdc sh -c 'mysql -h$TGT_HOST -u$TGT_USER -p$TGT_PASS $TGT_DB -e "SELECT COUNT(*) FROM $TARGET_TABLE"'
```

## Management Commands

```bash
# Stop CDC
docker stop mysql-cdc

# Start CDC again (resumes from checkpoint)
docker start mysql-cdc

# Remove CDC container
docker rm -f mysql-cdc

# Restart CDC (full reload)
docker rm -f mysql-cdc && ./run-external-db.sh
```

## Prerequisites

### Source Database Requirements:
```sql
-- 1. Enable binary logging (add to my.cnf and restart MySQL)
[mysqld]
server-id = 1
log-bin = mysql-bin
binlog_format = ROW

-- 2. Create CDC user with replication privileges
CREATE USER 'cdc_user'@'%' IDENTIFIED BY 'your_password';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc_user'@'%';
FLUSH PRIVILEGES;
```

### Target Database Requirements:
```sql
-- Create CDC user with write privileges
CREATE USER 'cdc_user'@'%' IDENTIFIED BY 'your_password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER ON your_database.* TO 'cdc_user'@'%';
FLUSH PRIVILEGES;
```

### Network Requirements:
- Docker host must be able to reach both source and target MySQL servers
- Firewall must allow connections to MySQL ports (default 3306)
- Test connectivity:
  ```bash
  telnet source-host 3306
  telnet target-host 3306
  ```

## Example Configuration

```bash
# Production Example
SRC_HOST="prod-mysql.company.com"
SRC_PORT="3306"
SRC_USER="cdc_reader"
SRC_PASS="SecurePassword123"
SRC_DATABASE="production"
SRC_TABLE="orders"

TGT_HOST="analytics-mysql.company.com"
TGT_PORT="3306"
TGT_USER="cdc_writer"
TGT_PASS="SecurePassword456"
TGT_DATABASE="analytics"
TGT_TABLE="orders_replica"

PARALLEL_WORKERS="16"     # For 20M+ records
BATCH_SIZE="20000"        # Large batches for speed
```

## Troubleshooting

### Connection Failed
```bash
# Test source connection
docker run --rm mysql:8.0 mysql -h source-host -u user -p database -e "SELECT 1;"

# Test target connection
docker run --rm mysql:8.0 mysql -h target-host -u user -p database -e "SELECT 1;"
```

### Binlog Not Enabled
```bash
# Check on source
docker run --rm mysql:8.0 mysql -h source-host -u user -p -e "SHOW VARIABLES LIKE 'log_bin';"
# Should show: log_bin = ON
```

### Permissions Error
```bash
# Verify source privileges
docker run --rm mysql:8.0 mysql -h source-host -u user -p -e "SHOW GRANTS;"
# Should include: REPLICATION SLAVE, REPLICATION CLIENT

# Verify target privileges
docker run --rm mysql:8.0 mysql -h target-host -u user -p -e "SHOW GRANTS;"
# Should include: INSERT, UPDATE, DELETE on target database
```

## Performance Tuning

For large datasets (20-30M records):

```bash
PARALLEL_WORKERS="16"      # Use more workers
BATCH_SIZE="20000"         # Larger batches
CHECKPOINT_INTERVAL="30"   # Less frequent checkpoints
```

See `PERFORMANCE_GUIDE.md` for detailed optimization strategies.
