# MySQL CDC - Quick Start Guide

## 5-Minute Setup

### 1. Configure Your Databases

Edit `run-external-db.sh`:

```bash
# Source Database (where data comes from)
SRC_HOST="localhost"
SRC_PORT="3306"
SRC_USER="root"
SRC_PASS="your_password"
SRC_DATABASE="your_database"
SRC_TABLE="your_table"

# Target Database (where data goes to)
TGT_HOST="localhost"
TGT_PORT="3307"
TGT_USER="root"
TGT_PASS="your_password"
TGT_DATABASE="your_database"
TGT_TABLE="your_table"
```

### 2. Start CDC

```bash
chmod +x cdc-control.sh
./cdc-control.sh start
```

That's it! CDC is now running.

---

## Common Operations

### Check Status
```bash
./cdc-control.sh status
```

### View Logs
```bash
./cdc-control.sh logs        # Last 50 lines
./cdc-control.sh follow      # Live logs (Ctrl+C to exit)
```

### Stop CDC (Preserves Checkpoint)
```bash
./cdc-control.sh stop
```

### Resume After Stop (Continues from Checkpoint)
```bash
./cdc-control.sh start       # or 'resume'
```

**✅ This will NOT drop tables or redo full load!**
- Reads last checkpoint from database
- Resumes CDC from that binlog position
- No data loss, no duplicate work

### Restart After Code Update (Preserves Data)
```bash
# Edit your code or config
./cdc-control.sh restart
```

**✅ This will:**
- Stop the container
- Rebuild Docker image with new code
- Resume from last checkpoint
- **NOT** drop any tables

### Fresh Start (Drops Everything - DESTRUCTIVE)
```bash
./cdc-control.sh fresh
```

**⚠️ This will:**
- Stop the container
- **DROP target table** 
- **DROP checkpoints**
- Perform full load from scratch
- Start CDC from beginning

Use this only when you want to completely reset replication.

---

## Understanding Resume Behavior

### What Gets Preserved?

When you run `stop` + `start`/`resume`:

✅ **Preserved:**
- All replicated data in target table
- Checkpoint (binlog position)
- Full load progress
- Schema

❌ **NOT Preserved:**
- Running container (removed)
- In-memory state

### How Resume Works

1. **On Stop:**
   ```
   checkpoint table = mysql-bin.000004:543085831
   target table    = 10,015 rows
   ```

2. **User Stops Container:**
   ```bash
   ./cdc-control.sh stop
   ```

3. **Later, User Resumes:**
   ```bash
   ./cdc-control.sh start
   ```

4. **CDC Reads Checkpoint:**
   ```
   Found checkpoint: mysql-bin.000004:543085831
   Skipping full load (table exists with data)
   Starting CDC from position 543085831
   ```

5. **Result:**
   - No full load performed
   - Continues from exact binlog position
   - Picks up all changes since checkpoint

---

## Use Cases

### Scenario 1: Accidental Stop
```bash
# Oops, stopped by mistake
./cdc-control.sh stop

# No problem, just resume
./cdc-control.sh start
# ✅ Continues from checkpoint, no data loss
```

### Scenario 2: Server Maintenance
```bash
# Need to reboot server
./cdc-control.sh stop

# After reboot
./cdc-control.sh start
# ✅ Resumes replication automatically
```

### Scenario 3: Config Change (Without Full Reload)
```bash
# Change batch size or workers
nano run-external-db.sh

# Apply changes
./cdc-control.sh restart
# ✅ Uses new config, preserves data
```

### Scenario 4: Bug in CDC Code
```bash
# Fix bug in src/cdc.go
nano src/cdc.go

# Deploy fix
./cdc-control.sh restart
# ✅ Continues from checkpoint with fixed code
```

### Scenario 5: Need Fresh Start (Data Issues)
```bash
# Target table is corrupted or out of sync
./cdc-control.sh fresh
# Type 'yes' to confirm
# ⚠️ Drops everything and starts fresh
```

---

## Checkpoint Details

### View Current Checkpoint
```bash
mysql -h target_host -P 3307 -u user -p -e "
  SELECT * FROM target_database.cdc_checkpoints;
"
```

Output:
```
+------------------------+-----------------+-------------+---------------------+
| table_key              | binlog_file     | binlog_pos  | updated_at          |
+------------------------+-----------------+-------------+---------------------+
| source_db.source_table | mysql-bin.000004| 543085831   | 2025-11-29 20:15:45 |
+------------------------+-----------------+-------------+---------------------+
```

### How Checkpoints Work

1. **Written Every:**
   - 100 CDC events, OR
   - 5 seconds (whichever comes first)

2. **Stored In:**
   - `target_database.cdc_checkpoints` table
   - Auto-created on first run

3. **Used For:**
   - Resume after stop
   - Resume after crash
   - Resume after network failure

---

## Testing Resume

### Test 1: Stop and Resume
```bash
# 1. Start CDC
./cdc-control.sh start

# 2. Wait for full load to complete
./cdc-control.sh logs | grep "Starting CDC"

# 3. Insert test data
mysql -h source_host -e "INSERT INTO source_db.source_table ..."

# 4. Verify it replicated
mysql -h target_host -e "SELECT COUNT(*) FROM target_db.target_table;"

# 5. Stop CDC
./cdc-control.sh stop

# 6. Insert more data (while CDC is stopped)
mysql -h source_host -e "INSERT INTO source_db.source_table ..."

# 7. Resume CDC
./cdc-control.sh start

# 8. Verify new data replicated
mysql -h target_host -e "SELECT COUNT(*) FROM target_db.target_table;"
# ✅ Should include data inserted while CDC was stopped
```

### Test 2: Restart with Code Change
```bash
# 1. CDC is running
./cdc-control.sh status

# 2. Make code change
echo "// test change" >> src/cdc.go

# 3. Restart
./cdc-control.sh restart

# 4. Verify data preserved
mysql -h target_host -e "SELECT COUNT(*) FROM target_db.target_table;"
# ✅ All data still there, no full reload
```

---

## FAQ

**Q: Will `start` redo the full load?**
A: No! If checkpoint exists and target table has data, it skips full load and resumes CDC.

**Q: What if I want to force full load?**
A: Use `./cdc-control.sh fresh` to drop tables and start over.

**Q: Can I change the table name without full reload?**
A: No, table name change requires fresh start. Checkpoint is per-table.

**Q: What happens if source MySQL restarts?**
A: Binlog files are preserved. Just run `./cdc-control.sh start` to resume.

**Q: How do I know if it resumed or started fresh?**
A: Check logs:
```bash
./cdc-control.sh logs | head -20
```
- If resuming: `"Starting CDC from mysql-bin.XXXXX:YYYYY"`
- If fresh: `"Full-load attempt 1/3"`

**Q: Does checkpoint survive Docker image rebuild?**
A: Yes! Checkpoint is in MySQL database, not in container.

---

## Monitoring Resume

### Check if Full Load or Resume

```bash
# View startup logs
./cdc-control.sh logs | grep -E "Full-load|Starting CDC"
```

**Full Load (Fresh Start):**
```
2025/11/29 20:15:45 Full-load attempt 1/3
2025/11/29 20:15:45 Copying schema to target table
2025/11/29 20:15:45 Starting streaming load
```

**Resume (From Checkpoint):**
```
2025/11/29 20:15:45 Starting CDC from mysql-bin.000004:543085831
```

### Monitor Lag

```bash
# Compare source and target counts
mysql -h source_host -e "SELECT COUNT(*) as src FROM source_db.source_table;" && \
mysql -h target_host -e "SELECT COUNT(*) as tgt FROM target_db.target_table;"
```

---

## Command Summary

| Command | Data Preserved? | Use Case |
|---------|----------------|----------|
| `start` | ✅ Yes (resumes) | Start CDC, resume after stop |
| `resume` | ✅ Yes (same as start) | Explicitly resume from checkpoint |
| `stop` | ✅ Yes | Stop container, keep data |
| `restart` | ✅ Yes (resumes) | Rebuild + resume (code changes) |
| `fresh` | ❌ No (drops all) | Complete reset, start over |
| `status` | N/A | Check current status |
| `logs` | N/A | View logs |
| `follow` | N/A | Watch logs live |

---

## Troubleshooting

### Resume Not Working

**Symptom:** Full load runs every time

**Check:**
```bash
mysql -h target_host -e "SELECT * FROM target_db.cdc_checkpoints;"
```

**If empty:** Checkpoint not being written
- Check logs for errors
- Verify target database permissions

**Solution:**
```bash
./cdc-control.sh restart
```

### Want to Force Full Reload

```bash
# Option 1: Use fresh command
./cdc-control.sh fresh

# Option 2: Manual
./cdc-control.sh stop
mysql -h target_host -e "
  TRUNCATE TABLE target_db.target_table;
  DELETE FROM target_db.cdc_checkpoints 
  WHERE table_key = 'source_db.source_table';
"
./cdc-control.sh start
```

---

**Need help?** Check the full documentation in `README.md`
