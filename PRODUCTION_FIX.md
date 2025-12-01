# Quick Fix for Production Server

## ✅ Verified Working Locally
The fix successfully loads **1.1M+ rows** without timeout (passed 620K milestone).

## 🔧 Production Server Steps

Run these commands on your production server:

```bash
# 1. Navigate to project directory
cd /path/to/mysql-cdc-go-single-table

# 2. Pull latest code (includes all fixes)
git pull origin main

# 3. Run verification script to diagnose issues
chmod +x verify-fix.sh
./verify-fix.sh

# 4. Rebuild Docker image (CRITICAL - this is likely missing)
docker build -t mysql-cdc-go-single-table .

# 5. Stop old container
docker stop mysql-cdc 2>/dev/null
docker rm mysql-cdc 2>/dev/null

# 6. Clear checkpoint for fresh test (optional but recommended)
mysql -h 127.0.0.1 -P 3307 -uroot -p<password> <database> -e "
TRUNCATE TABLE channel_txn_v2_temp;
DROP TABLE IF EXISTS _cdc_checkpoint;
DROP TABLE IF EXISTS full_load_progress;
"

# 7. Start CDC with new image
./run-external-db.sh
```

## 🔍 Why It's Still Failing on Production

Your production server is using the **old Docker image** (30s timeout). The error timing confirms this:
- Started: 06:45:10
- Failed: 06:45:40
- **Duration: exactly 30 seconds** ← Old timeout!

## ✅ What the Fix Does

1. **DSN Timeout**: 30s → 300s (5 minutes)
2. **Connection Pool**: `SetConnMaxLifetime(10 * time.Minute)` 
3. **Connection Validation**: Ping before each query batch

## 🎯 Verification After Deployment

After running the steps above, verify the fix:

```bash
# Check if container is using new image
docker ps -a | grep mysql-cdc

# Check logs - should see progress past 620K
docker logs -f mysql-cdc

# Monitor progress
watch -n 2 'docker logs mysql-cdc 2>&1 | grep "Streaming load progress" | tail -5'
```

You should see it progress smoothly:
```
620000 rows loaded
630000 rows loaded  ← Should pass without timeout
640000 rows loaded
...
```

## 📊 Expected Timeline

For ~1M rows at ~10K rows per batch:
- First 100K: ~1-2 minutes (fast)
- 100K-500K: ~2-3 seconds per batch
- 500K-1M: ~4-6 seconds per batch (slower due to offset)
- **Total: ~6-8 minutes** for full million

## ⚠️ If Still Failing After This

If timeout persists after rebuilding:

1. **Check MySQL server timeout settings:**
   ```sql
   SHOW VARIABLES LIKE '%timeout%';
   -- Look for: wait_timeout, net_read_timeout, net_write_timeout
   ```

2. **Add indexes to source table:**
   ```sql
   -- Check existing indexes
   SHOW INDEX FROM channel_txn_v2_temp;
   
   -- If no primary key index, queries will be slow
   -- Add composite index on primary key columns
   ```

3. **Reduce batch size** (if table has no good indexes):
   ```bash
   # In run-external-db.sh, change:
   BATCH_SIZE="5000"  # Reduce from 10000
   ```

## 🆘 Need Help?

Share the output of `./verify-fix.sh` to diagnose any remaining issues.
