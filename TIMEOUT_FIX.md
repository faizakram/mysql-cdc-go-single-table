# Timeout Fix for Large Dataset Full Load

## Problem
The CDC was failing at ~620,000 rows during full load with the error:
```
[mysql] packets.go:37: read tcp 127.0.0.1:46964->127.0.0.1:3306: i/o timeout
Full-load attempt failed: query failed at offset 620000: invalid connection
```

## Root Cause
The timeout occurred due to **three separate issues**:

1. **DSN Timeout Parameters**: The original 30-second timeouts were too short
2. **Connection Pool Settings**: Missing configuration for long-running queries
3. **Stale Connection Detection**: No validation before executing queries

## Solution Applied

### 1. DSN Timeout Parameters (config.go + run-external-db.sh)
```go
// Increased timeouts from 30s to 300s (5 minutes)
readTimeout=300s   // Allows queries to run for up to 5 minutes
writeTimeout=300s  // Allows batch inserts to take up to 5 minutes
timeout=60s        // Connection establishment timeout
```

### 2. Connection Pool Settings (db.go - OpenDB function)
```go
db.SetMaxOpenConns(25)                  // Limit concurrent connections
db.SetMaxIdleConns(10)                  // Keep connections ready
db.SetConnMaxLifetime(10 * time.Minute) // Allow long-lived connections
db.SetConnMaxIdleTime(5 * time.Minute)  // Close idle after 5 min
```

**Why this matters**: Without `SetConnMaxLifetime`, the Go MySQL driver would close connections after the default timeout (typically 30 seconds), causing "invalid connection" errors even with proper DSN timeouts.

### 3. Connection Validation (full_load.go - streamingLoad)
```go
// Ping database before each batch to detect stale connections
if err := srcDB.Ping(); err != nil {
    log.Printf("Warning: source database connection lost, reconnecting...")
    return fmt.Errorf("connection lost at offset %d: %v", offset, err)
}
```

## Deployment Instructions for Production Server

### Step 1: Pull Latest Code
```bash
cd /path/to/mysql-cdc-go-single-table
git pull origin main
```

### Step 2: Rebuild Docker Image
```bash
docker build -t mysql-cdc-go-single-table .
```

### Step 3: Stop Current CDC (if running)
```bash
docker stop mysql-cdc 2>/dev/null || true
docker rm mysql-cdc 2>/dev/null || true
```

### Step 4: Clear Target Table (for fresh full load)
```bash
mysql -h <target-host> -P <target-port> -u<user> -p<password> <database> -e "
TRUNCATE TABLE <target_table>;
DROP TABLE IF EXISTS _cdc_checkpoint;
DROP TABLE IF EXISTS full_load_progress;
"
```

### Step 5: Start CDC with New Image
```bash
# If using run-external-db.sh
./run-external-db.sh

# OR if using docker-compose
docker-compose up -d

# OR if using manual docker run
docker run -d --name mysql-cdc --network host \
  -e SRC_DSN="user:pass@tcp(host:port)/?maxAllowedPacket=67108864&timeout=60s&readTimeout=300s&writeTimeout=300s" \
  -e TGT_DSN="user:pass@tcp(host:port)/?maxAllowedPacket=67108864&timeout=60s&readTimeout=300s&writeTimeout=300s" \
  -e SRC_DB="your_db" \
  -e TGT_DB="your_db" \
  -e SRC_TABLE="your_table" \
  -e TARGET_TABLE="your_target_table" \
  -e PARALLEL_WORKERS="8" \
  -e BATCH_SIZE="10000" \
  mysql-cdc-go-single-table
```

### Step 6: Monitor Progress
```bash
# Watch logs
docker logs -f mysql-cdc

# Or use tail to see recent progress
docker logs mysql-cdc 2>&1 | grep "Streaming load progress" | tail -20

# Check health endpoint
curl http://localhost:8080/health
curl http://localhost:8080/metrics
```

## Expected Behavior After Fix

1. **Full load will progress smoothly** through 620K+ rows without timeout
2. **Each batch query** can take up to 5 minutes (300s) to complete
3. **Connection validation** prevents "invalid connection" errors
4. **Progress logging** every 10K rows for monitoring
5. **Automatic retry** on transient connection issues

## Performance Tuning (if needed)

### If queries still timeout at high offsets (>1M rows):

1. **Add indexes** on primary key columns used in ORDER BY:
   ```sql
   -- Check current indexes
   SHOW INDEX FROM your_table;
   
   -- Add composite index if needed
   CREATE INDEX idx_pk_cols ON your_table(pk_col1, pk_col2);
   ```

2. **Reduce batch size** for faster queries:
   ```bash
   # In run-external-db.sh or environment variable
   BATCH_SIZE="5000"  # Reduce from 10000
   ```

3. **Increase timeout further** if queries legitimately need more time:
   ```bash
   # In DSN
   readTimeout=600s  # 10 minutes instead of 5
   ```

## Verification

After deployment, verify the fix is working:

```bash
# 1. Check Docker image was rebuilt (should show recent date)
docker images mysql-cdc-go-single-table

# 2. Verify DSN contains new timeouts
docker logs mysql-cdc 2>&1 | head -20

# 3. Monitor full load progress past 620K
docker logs -f mysql-cdc | grep "Streaming load progress"

# 4. Check connection pool is configured
docker exec mysql-cdc ps aux  # Should show single process with proper settings
```

## Commits Applied

- **553886b**: Fix: Increase DSN timeout parameters to support large datasets
- **f931efb**: Fix: Add connection pool settings and retry logic for large queries

## Questions or Issues?

If the timeout still occurs after applying this fix:

1. Check if MySQL server has its own query timeout settings:
   ```sql
   SHOW VARIABLES LIKE '%timeout%';
   ```

2. Monitor MySQL slow query log to see actual query execution times

3. Consider if the source table needs additional indexes for efficient ORDER BY

4. Verify network stability between CDC container and MySQL servers
