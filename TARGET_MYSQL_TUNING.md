# Target MySQL Performance Tuning

## Quick Setup (Run Once on Target MySQL Server)

Connect to your **target MySQL server** and run these commands as a user with SUPER privilege:

```sql
-- Connect to target MySQL
mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword

-- Set performance optimizations (GLOBAL settings)
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
SET GLOBAL innodb_buffer_pool_size = 4294967296;  -- 4GB (adjust based on RAM)
SET GLOBAL max_allowed_packet = 1073741824;       -- 1GB
SET GLOBAL innodb_log_buffer_size = 67108864;     -- 64MB
SET GLOBAL innodb_write_io_threads = 8;
SET GLOBAL innodb_flush_neighbors = 0;
```

## Explanation

### `innodb_flush_log_at_trx_commit = 2` (Most Important!)
- **Default**: 1 (safest, but SLOW - flushes to disk on every commit)
- **Setting**: 2 (writes to log file, flushes to disk every second)
- **Impact**: **2-3x faster** inserts
- **Trade-off**: May lose 1 second of data if MySQL crashes (acceptable for bulk load)

### `innodb_buffer_pool_size = 4GB`
- **Default**: Usually 128MB (too small!)
- **Recommended**: 50-80% of available RAM
- **Impact**: Faster reads/writes, better caching
- If you have 8GB RAM → set to 4GB
- If you have 16GB RAM → set to 8-12GB

### `max_allowed_packet = 1GB`
- Allows larger INSERT statements
- Prevents "Packet too large" errors

### Other Settings
- `innodb_log_buffer_size`: Larger buffer for transaction logs
- `innodb_write_io_threads`: More threads for disk writes
- `innodb_flush_neighbors`: Disable for SSD (faster)

## Make Settings Permanent

Add to your MySQL config file (`/etc/mysql/my.cnf` or `/etc/my.cnf`):

```ini
[mysqld]
innodb_flush_log_at_trx_commit = 2
innodb_buffer_pool_size = 4G
max_allowed_packet = 1G
innodb_log_buffer_size = 64M
innodb_write_io_threads = 8
innodb_flush_neighbors = 0
```

Then restart MySQL:
```bash
sudo systemctl restart mysql
```

## Verify Settings

```sql
SHOW VARIABLES LIKE 'innodb_flush_log_at_trx_commit';
SHOW VARIABLES LIKE 'innodb_buffer_pool_size';
SHOW VARIABLES LIKE 'max_allowed_packet';
```

## Restore to Safe Settings After Bulk Load (Optional)

```sql
SET GLOBAL innodb_flush_log_at_trx_commit = 1;  -- Back to safest mode
```

## Expected Performance Improvement

| Setting | Before | After | Speedup |
|---------|--------|-------|---------|
| innodb_flush_log_at_trx_commit | 1 | 2 | 2-3x |
| innodb_buffer_pool_size | 128MB | 4GB | 2-5x |
| Overall | - | - | **4-10x faster** |

## Important Notes

- These settings require **SUPER** or **SYSTEM_VARIABLES_ADMIN** privilege
- Cannot be set from application code (security restriction)
- Must be set on the **target** MySQL server (port 3307)
- **Safe** for bulk loading scenarios
- Can be reverted after load completes if needed

## Quick Command (Copy & Paste)

```bash
# Run on target MySQL server
mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword -e "
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
SET GLOBAL innodb_buffer_pool_size = 4294967296;
SET GLOBAL max_allowed_packet = 1073741824;
SET GLOBAL innodb_log_buffer_size = 67108864;
SET GLOBAL innodb_write_io_threads = 8;
SET GLOBAL innodb_flush_neighbors = 0;
"

# Verify
mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword -e "
SELECT @@innodb_flush_log_at_trx_commit,
       @@innodb_buffer_pool_size / 1024 / 1024 / 1024 as buffer_pool_gb,
       @@max_allowed_packet / 1024 / 1024 as max_packet_mb;
"
```

## Without These Settings

Your current performance: **208 rows/second**

## With These Settings + Application Optimizations

Expected performance: **8,000-12,000 rows/second**

**15 million records: 20-30 minutes** (instead of 20 hours!)
