# Resume Functionality for Large-Scale Replication

## Overview

For production environments with 250-300+ tables and large datasets, the replication scripts now support **resume from failure point** with comprehensive progress tracking. This ensures you can handle interruptions gracefully and continue from where you left off.

## Key Features

### 1. **Progress Tracking**
- JSON-based progress log tracks every table's status
- Records: completion status, row counts, timestamps, errors
- Separate tracking for schema and data phases
- Progress persisted to disk automatically

### 2. **Resume from Failure**
- Automatically detects last failed/incomplete table
- Resumes processing from failure point
- Skips already completed tables
- No data duplication or data loss

### 3. **Partial Data Handling**
- Detects tables with partial data (interrupted mid-sync)
- **Automatically truncates** partial tables before re-sync
- Ensures data consistency and integrity
- Option to skip truncation if needed

### 4. **Manual Table Selection**
- Start processing from any specific table
- Useful for targeted re-runs or testing
- Maintains processing order

### 5. **Progress Reporting**
- Real-time progress display: "Processing table 45/300"
- Periodic summary every 10 tables
- Final comprehensive summary with statistics

## Usage

### Schema Replication (`replicate-schema.py`)

#### Basic Commands

```bash
# Normal run (processes all tables)
python replicate-schema.py

# Resume from last failure
python replicate-schema.py --resume

# Start from specific table
python replicate-schema.py --start-from-table Orders

# Reset progress and start fresh
python replicate-schema.py --reset

# View help
python replicate-schema.py --help
```

#### Example Scenarios

**Scenario 1: Initial run gets interrupted at table 45/300**
```bash
# Run interrupted
python replicate-schema.py
# ... processing stops at table 45

# Resume from table 45
python replicate-schema.py --resume
# Automatically continues from table 45
```

**Scenario 2: Skip first 100 tables, start from table 101**
```bash
python replicate-schema.py --start-from-table Products
```

**Scenario 3: Start completely fresh**
```bash
python replicate-schema.py --reset  # Clears progress
python replicate-schema.py          # Starts from beginning
```

### Data Synchronization (`sync-data.py`)

#### Basic Commands

```bash
# Normal run (syncs all tables)
python sync-data.py

# Resume from last failure (truncates partial tables)
python sync-data.py --resume

# Resume without truncating partial tables
python sync-data.py --resume --no-truncate

# Start from specific table
python sync-data.py --start-from-table Orders

# Reset progress and start fresh
python sync-data.py --reset

# View help
python sync-data.py --help
```

#### Example Scenarios

**Scenario 1: Sync interrupted with partial data**
```bash
# Run interrupted after syncing 50,000 rows of Orders table
python sync-data.py
# ... connection lost

# Resume - truncates Orders table and re-syncs from scratch
python sync-data.py --resume
# Orders table truncated, all data re-synced for consistency
```

**Scenario 2: Resume without truncation (risky - may have partial data)**
```bash
python sync-data.py --resume --no-truncate
# WARNING: May result in partial data - use only if certain
```

**Scenario 3: Large dataset - sync first 100 tables, then continue**
```bash
# First batch
python sync-data.py --start-from-table Customers
# ... let it run to table 100, then stop

# Continue from table 101
python sync-data.py --start-from-table Products
```

## Progress Files

### Schema Replication Progress
**File:** `schema_replication_progress.json`

```json
{
  "session_start": "2025-12-16T10:30:00",
  "last_updated": "2025-12-16T11:45:00",
  "tables": {
    "Customers": {
      "schema": {
        "status": "completed",
        "completed_at": "2025-12-16T10:31:00",
        "rows_synced": 0,
        "error": null
      }
    },
    "Orders": {
      "schema": {
        "status": "failed",
        "failed_at": "2025-12-16T10:32:00",
        "rows_synced": 0,
        "error": "column type mismatch"
      }
    }
  }
}
```

### Data Synchronization Progress
**File:** `data_sync_progress.json`

```json
{
  "session_start": "2025-12-16T12:00:00",
  "last_updated": "2025-12-16T14:30:00",
  "tables": {
    "Customers": {
      "data": {
        "status": "completed",
        "completed_at": "2025-12-16T12:15:00",
        "rows_synced": 150000,
        "error": null
      }
    },
    "Orders": {
      "data": {
        "status": "in_progress",
        "started_at": "2025-12-16T14:25:00",
        "rows_synced": 50000,
        "error": null
      }
    }
  }
}
```

## Progress Display

### During Execution

```
============================================================
📊 PROGRESS SUMMARY
============================================================
Total tables:     300
✅ Completed:     125
❌ Failed:        2
⏳ In Progress:   1
📋 Remaining:     172
📈 Progress:      125/300 (41%)
============================================================

[126/300] Processing: Orders
📊 Syncing dbo.Orders → campaign_final_v3.orders
  🔍 Reading from MS SQL...
  🗑️  Truncating target table...
  📝 Writing to PostgreSQL...
  ✅ 85,432 rows synced successfully
```

### Final Summary

```
============================================================
📊 PROGRESS SUMMARY
============================================================
Total tables:     300
✅ Completed:     298
❌ Failed:        2
⏳ In Progress:   0
📋 Remaining:     0
📈 Progress:      298/300 (99%)
============================================================

============================================================
📊 Synchronization Summary
============================================================
Total tables: 300
✅ Successful: 298
⏭️  Skipped: 0
❌ Failed: 2
📝 Total rows synced: 45,234,567
⏱️  Duration: 3245.67 seconds
⚡ Throughput: 13,935 rows/second
============================================================
⚠️  2 tables failed to sync
💡 Use --resume to continue from failure point
```

## Best Practices

### For 250-300 Tables

1. **Initial Schema Replication**
   ```bash
   # Run schema replication first
   python replicate-schema.py
   
   # If interrupted, resume
   python replicate-schema.py --resume
   ```

2. **Batch Data Synchronization**
   ```bash
   # Sync in batches if dataset is massive
   # Batch 1: Tables 1-100
   python sync-data.py --start-from-table Customers
   
   # Batch 2: Tables 101-200
   python sync-data.py --start-from-table Orders
   
   # Batch 3: Tables 201-300
   python sync-data.py --start-from-table Products
   ```

3. **Monitor Progress**
   - Check progress files periodically
   - Look for failed tables in JSON logs
   - Review error messages for failed tables

4. **Handle Failures**
   ```bash
   # After fixing issues (network, permissions, etc.)
   python sync-data.py --resume
   
   # Partial data? Truncate and re-sync
   python sync-data.py --resume  # Default: truncates partial tables
   ```

### For Large Datasets

1. **Tune Batch Size**
   Edit `.env`:
   ```env
   BATCH_SIZE=1000  # Increase for faster throughput
   ```

2. **Run During Off-Peak Hours**
   - Schedule for low-traffic periods
   - Less contention, better performance

3. **Monitor Memory Usage**
   - Large batches = more memory
   - Adjust `BATCH_SIZE` based on available RAM

4. **Network Stability**
   - Use stable network connection
   - If resuming frequently, consider VPN or direct connection

## Troubleshooting

### Progress File Corrupted

```bash
# Reset and start fresh
python replicate-schema.py --reset
python replicate-schema.py
```

### Stuck on Same Table

```bash
# Check error in progress JSON file
cat data_sync_progress.json | grep -A5 "TableName"

# Fix issue (permissions, schema, data type)
# Then resume
python sync-data.py --resume
```

### Partial Data Issue

```bash
# Truncate and re-sync (recommended)
python sync-data.py --resume

# Or skip truncation (use carefully)
python sync-data.py --resume --no-truncate
```

### Want to Skip Failed Tables

```bash
# Manually edit progress JSON
# Change status from "failed" to "completed" for tables you want to skip

# Then resume
python sync-data.py --resume
```

## Performance Expectations

### Schema Replication
- **Small tables (<10 columns):** ~1-2 seconds per table
- **Large tables (>50 columns):** ~5-10 seconds per table
- **300 tables:** ~15-30 minutes total

### Data Synchronization
- **Throughput:** 5,000-15,000 rows/second (depending on network, data types)
- **Small tables (<10,000 rows):** <1 minute
- **Medium tables (100,000 rows):** 5-10 minutes
- **Large tables (1,000,000+ rows):** 1-3 hours
- **300 tables with mixed sizes:** Several hours to days

## Architecture

### Components

1. **ProgressTracker Class** (`progress.py`)
   - JSON-based persistence
   - Table-by-table status tracking
   - Error logging with timestamps
   - Summary generation

2. **Resume Logic** (both scripts)
   - CLI argument parsing
   - Start index calculation
   - Completed table skipping
   - Progress display

3. **Truncate on Partial** (`sync-data.py`)
   - Detects partial data
   - Truncates target table
   - Re-syncs from source
   - Ensures consistency

### Data Flow

```
┌─────────────────┐
│ Start Script    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Parse Args      │
│ --resume        │
│ --start-from    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Load Progress   │
│ from JSON       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Determine Start │
│ Index           │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Process Tables  │
│ (loop)          │
└────────┬────────┘
         │
         ├──────► Skip if before start_index
         │
         ├──────► Skip if completed
         │
         ├──────► Truncate if partial (data phase)
         │
         ├──────► Process table
         │
         ├──────► Mark completed/failed in progress
         │
         └──────► Repeat
```

## Conclusion

The resume functionality provides a production-ready solution for large-scale database replication with:
- ✅ **Reliability:** Never lose progress, resume from failure
- ✅ **Consistency:** Truncate partial data automatically
- ✅ **Visibility:** Real-time progress tracking
- ✅ **Flexibility:** Manual table selection
- ✅ **Performance:** Optimized for 250-300+ tables

Perfect for production environments with large datasets and long-running replication jobs!
