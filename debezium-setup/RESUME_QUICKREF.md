# Quick Reference: Resume Feature

## Command Cheat Sheet

### replicate-schema.py (Schema Replication)

```bash
# Normal run
python replicate-schema.py

# Resume from last failure
python replicate-schema.py --resume

# Start from specific table
python replicate-schema.py --start-from-table TableName

# Reset and start fresh
python replicate-schema.py --reset

# Get help
python replicate-schema.py --help
```

### sync-data.py (Data Synchronization)

```bash
# Normal run
python sync-data.py

# Resume with truncate (recommended for partial data)
python sync-data.py --resume

# Resume without truncate (risky)
python sync-data.py --resume --no-truncate

# Start from specific table
python sync-data.py --start-from-table TableName

# Reset and start fresh
python sync-data.py --reset

# Get help
python sync-data.py --help
```

## Common Workflows

### 1. Fresh Start (All 300 Tables)

```bash
# Step 1: Reset any previous progress
python replicate-schema.py --reset
python sync-data.py --reset

# Step 2: Create all schemas
python replicate-schema.py

# Step 3: Sync all data
python sync-data.py
```

### 2. Resume After Interruption

```bash
# If interrupted during schema creation
python replicate-schema.py --resume

# If interrupted during data sync
python sync-data.py --resume  # Truncates partial tables
```

### 3. Process in Batches

```bash
# Batch 1: Tables 1-100 (Customers to Orders)
python sync-data.py --start-from-table Customers

# Batch 2: Tables 101-200 (Products to Inventory)
python sync-data.py --start-from-table Products

# Batch 3: Tables 201-300 (Shipments to Logs)
python sync-data.py --start-from-table Shipments
```

### 4. Skip Failed Tables and Continue

```bash
# Manually edit progress JSON file
# Change failed table status from "failed" to "completed"
nano data_sync_progress.json

# Then continue
python sync-data.py
```

## Progress Files

| File | Purpose | Location |
|------|---------|----------|
| `schema_replication_progress.json` | Schema creation tracking | `debezium-setup/` |
| `data_sync_progress.json` | Data sync tracking | `debezium-setup/` |

## Output Examples

### Normal Run
```
[45/300] Processing: dbo.Orders
📊 Syncing dbo.Orders → target.orders
  ✅ 125,432 rows synced successfully
```

### Skip Completed
```
⏭️  Skipping dbo.Customers (already completed) [1/300]
```

### Progress Summary (Every 10 Tables)
```
============================================================
📊 PROGRESS SUMMARY
============================================================
Total tables:     300
✅ Completed:     125
❌ Failed:        2
⏳ In Progress:   0
📋 Remaining:     173
📈 Progress:      125/300 (41%)
============================================================
```

### Final Summary
```
============================================================
📊 Synchronization Summary
============================================================
Total tables: 300
✅ Processed successfully: 295
⏭️  Skipped (already done): 0
❌ Failed: 5
📝 Total rows synced: 12,345,678
⏱️  Duration: 3245.67 seconds
⚡ Throughput: 3,802 rows/second
============================================================
⚠️  5 tables failed to sync
💡 Use --resume to continue from failure point
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Progress file corrupted | `python sync-data.py --reset` |
| Stuck on same table | Check error in JSON, fix issue, then `--resume` |
| Partial data concerns | Use `--resume` (truncates automatically) |
| Want to skip failed tables | Edit JSON file, mark as "completed" |
| Start from middle | Use `--start-from-table TableName` |

## Key Features

✅ **Automatic Progress Tracking** - Every table logged
✅ **Resume from Failure** - Never lose progress
✅ **Partial Data Handling** - Auto-truncate for consistency
✅ **Manual Table Selection** - Start anywhere
✅ **Real-time Progress** - Know exactly where you are
✅ **Detailed Logging** - Timestamps, row counts, errors

## Performance Tips

1. **Batch Size**: Increase `BATCH_SIZE` in `.env` for faster throughput
   ```env
   BATCH_SIZE=2000  # Default: 1000
   ```

2. **Run During Off-Peak**: Less contention = better performance

3. **Monitor Progress**: Check JSON files periodically
   ```bash
   cat data_sync_progress.json | grep -c '"completed"'
   ```

4. **Network Stability**: Use wired connection for large datasets

## Production Example (300 Tables)

```bash
# Day 1: Start schema replication
nohup python replicate-schema.py > schema_log.txt 2>&1 &

# Day 2: Start data sync
nohup python sync-data.py > data_log.txt 2>&1 &

# Day 3: Check progress
tail -f data_log.txt
cat data_sync_progress.json | grep -c '"completed"'

# If interrupted, resume
python sync-data.py --resume
```

## Quick Progress Check

```bash
# Count completed tables
grep -c '"completed"' data_sync_progress.json

# Count failed tables
grep -c '"failed"' data_sync_progress.json

# View last 10 tables processed
tail -20 data_log.txt

# Check specific table status
grep -A5 '"TableName"' data_sync_progress.json
```
