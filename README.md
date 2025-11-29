<<<<<<< HEAD
# MySQL CDC Replication - Complete Documentation

## Table of Contents
1. [Overview](#overview)
2. [Features](#features)
3. [Prerequisites](#prerequisites)
4. [Installation](#installation)
5. [Configuration](#configuration)
6. [Quick Start](#quick-start)
7. [Advanced Usage](#advanced-usage)
8. [Troubleshooting](#troubleshooting)
9. [Performance Tuning](#performance-tuning)
10. [Architecture](#architecture)

---

## Overview

This MySQL CDC (Change Data Capture) solution provides real-time replication between MySQL databases, with special support for UTF-32 charset tables. It performs an initial full load followed by continuous CDC replication using MySQL binlog.

### Key Capabilities
- **Full Load**: Initial table copy with automatic resume on failure
- **CDC Replication**: Real-time replication of INSERT, UPDATE, DELETE operations
- **UTF-32 Support**: Automatic charset conversion for UTF-32 encoded columns
- **Large Dataset Optimization**: Optimized for 20-30 million record tables
- **Checkpoint Management**: Resume replication from last successful position

---

## Features

✅ **Full Load with Streaming**
- Handles tables with composite primary keys
- Memory-efficient streaming with configurable batch sizes
- Automatic retry with exponential backoff

✅ **Real-Time CDC**
- Binlog-based change capture
- Support for INSERT, UPDATE, DELETE operations
- Automatic NULL value handling

✅ **Charset Conversion**
- Automatic UTF-32 to UTF-8 conversion
- Big-endian UTF-32 support (MySQL standard)
- UTF-16 fallback for compatibility

✅ **Production Ready**
- Checkpoint-based resume capability
- Configurable retry logic
- Docker containerized deployment
- Easy start/stop management

---

## Prerequisites

### Required Software
- **Docker**: Version 20.10 or higher
- **MySQL Servers**: Version 5.7 or higher (source and target)
- **Bash**: For running control scripts

### MySQL Configuration (Source Database)

1. **Enable Binary Logging** - Edit MySQL config (`/etc/mysql/my.cnf` or `/etc/my.cnf`):
```ini
[mysqld]
server-id = 1
log_bin = mysql-bin
binlog_format = ROW
binlog_row_image = FULL
```

2. **Restart MySQL** after configuration changes:
```bash
sudo systemctl restart mysql
```

3. **Create Replication User** (optional but recommended):
```sql
CREATE USER 'cdc_user'@'%' IDENTIFIED BY 'SecurePassword123!';
GRANT REPLICATION SLAVE, REPLICATION CLIENT, SELECT ON *.* TO 'cdc_user'@'%';
FLUSH PRIVILEGES;
```

### Network Requirements
- Source MySQL must be accessible from Docker container
- Target MySQL must be accessible from Docker container
- If using `localhost`, ensure `--network host` is used in Docker

---

## Installation

### Step 1: Download/Clone the Project
```bash
cd /path/to/your/workspace
# Download or extract the project files
cd mysql-cdc-go-single-table
```

### Step 2: Make Scripts Executable
```bash
chmod +x cdc-control.sh
chmod +x run-external-db.sh
```

### Step 3: Verify Docker Installation
```bash
docker --version
# Should output: Docker version 20.10.x or higher
```

---

## Configuration

### Environment Variables

Edit the `run-external-db.sh` script to configure your environment:

```bash
# Source Database Configuration
SRC_HOST="localhost:3306"
SRC_USER="root"
SRC_PASS="your_source_password"
SRC_DB="your_database"
SRC_TABLE="your_table"

# Target Database Configuration  
TGT_HOST="localhost:3307"
TGT_USER="root"
TGT_PASS="your_target_password"
TGT_DB="your_database"
TGT_TABLE="your_table"

# Performance Settings (for 20-30M records)
PARALLEL_WORKERS=8
BATCH_SIZE=10000
```

### Configuration Parameters Explained

| Parameter | Default | Description |
|-----------|---------|-------------|
| `SRC_HOST` | `localhost:3306` | Source MySQL host:port |
| `SRC_USER` | `root` | Source MySQL username |
| `SRC_PASS` | `password` | Source MySQL password |
| `SRC_DB` | `database` | Source database name |
| `SRC_TABLE` | `table` | Source table name |
| `TGT_HOST` | `localhost:3307` | Target MySQL host:port |
| `TGT_USER` | `root` | Target MySQL username |
| `TGT_PASS` | `password` | Target MySQL password |
| `TGT_DB` | `database` | Target database name |
| `TGT_TABLE` | `table` | Target table name |
| `PARALLEL_WORKERS` | `8` | Number of parallel workers for full load |
| `BATCH_SIZE` | `10000` | Rows per batch during full load |
| `FULLLOAD_MAX_RETRIES` | `3` | Max retry attempts for full load |
| `CHECKPOINT_EVERY` | `100` | Write checkpoint every N events |

---

## Quick Start

### Starting CDC Replication

```bash
# Build and start CDC
./cdc-control.sh start
```

This will:
1. Build the Docker image
2. Start the CDC container
3. Perform full load (initial table copy)
4. Begin real-time CDC replication

### Checking Status

```bash
# View current status
./cdc-control.sh status
```

Output example:
```
═══════════════════════════════════════════════════════════
           MySQL CDC Status
═══════════════════════════════════════════════════════════

✓ CDC Status: RUNNING

Container Details:
  ID: adc12f9a947b
  Image: mysql-cdc-go-single-table
  Started: 2 minutes ago
  Status: Up 2 minutes

Recent Activity (last 10 lines):
  2025/11/29 20:00:00 Starting CDC from mysql-bin.000004:543083437
  2025/11/29 20:00:05 Applied INSERT for 1 rows
  2025/11/29 20:00:10 Wrote checkpoint mysql-bin.000004:543085123

═══════════════════════════════════════════════════════════
```

### Stopping CDC

```bash
# Stop CDC gracefully
./cdc-control.sh stop
```

### Restarting CDC (After Code Changes)

```bash
# Rebuild and restart
./cdc-control.sh restart
```

### Viewing Logs

```bash
# View last 50 lines
./cdc-control.sh logs

# Follow logs in real-time
./cdc-control.sh follow
# Press Ctrl+C to exit
```

---

## Advanced Usage

### Resuming After Failure

CDC automatically resumes from the last checkpoint:

1. Stop CDC if running: `./cdc-control.sh stop`
2. Fix any issues (network, credentials, etc.)
3. Restart: `./cdc-control.sh start`

The system reads the checkpoint from `cdc_checkpoints` table in the target database and resumes from that binlog position.

### Manual Checkpoint Check

```bash
mysql -h target_host -u user -p -e "
  SELECT * FROM target_database.cdc_checkpoints 
  ORDER BY updated_at DESC LIMIT 5;
"
```

### Full Reload (Reset Everything)

If you need to start from scratch:

```bash
# 1. Stop CDC
./cdc-control.sh stop

# 2. Truncate target table and checkpoints
mysql -h target_host -u user -p <<EOF
  TRUNCATE TABLE target_database.target_table;
  DROP TABLE IF EXISTS target_database.cdc_checkpoints;
  DROP TABLE IF EXISTS target_database.full_load_progress;
EOF

# 3. Start fresh
./cdc-control.sh start
```

### Testing CDC with Sample Data

```sql
-- Insert test record in source database
INSERT INTO source_db.source_table (column1, column2, ...) 
VALUES ('test_value1', 'test_value2', ...);

-- Wait 2-3 seconds, then check target
SELECT * FROM target_db.target_table 
WHERE column1 = 'test_value1';
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. **Container Fails to Start**

**Check logs:**
```bash
./cdc-control.sh logs
```

**Common causes:**
- Invalid credentials → Verify username/password in `run-external-db.sh`
- Database not accessible → Check network connectivity
- Binlog not enabled → Enable binlog on source MySQL

**Solution:**
```bash
# Test source connection
mysql -h source_host -P 3306 -u source_user -p

# Test target connection  
mysql -h target_host -P 3307 -u target_user -p
```

#### 2. **"Data too long for column" Error**

This was the UTF-32 charset issue (now fixed). If you still see this:

**Check column charset:**
```sql
SHOW FULL COLUMNS FROM your_database.your_table;
```

**Verify fix is applied:**
```bash
# Check if latest code is deployed
./cdc-control.sh restart
```

#### 3. **CDC Stops Replicating**

**Check binlog position:**
```bash
# On source database
mysql -e "SHOW MASTER STATUS;"

# Check current checkpoint
mysql -h target_host -u user -p -e "
  SELECT * FROM target_db.cdc_checkpoints 
  WHERE table_key = 'source_db.source_table';
"
```

**Solution:**
```bash
./cdc-control.sh restart
```

#### 4. **Full Load Takes Too Long**

For tables > 10M rows, tune batch size:

Edit `run-external-db.sh`:
```bash
BATCH_SIZE=50000  # Increase from 10000
PARALLEL_WORKERS=16  # Increase from 8
```

Then restart:
```bash
./cdc-control.sh restart
```

#### 5. **Missing Records After Full Load**

**Verify counts:**
```bash
# Source count
mysql -h source_host -u user -p -e "
  SELECT COUNT(*) FROM source_db.source_table;
"

# Target count
mysql -h target_host -u user -p -e "
  SELECT COUNT(*) FROM target_db.target_table;
"
```

**If mismatch, do full reload** (see [Full Reload](#full-reload-reset-everything))

#### 6. **High CPU/Memory Usage**

**Reduce batch size and workers:**
```bash
# Edit run-external-db.sh
BATCH_SIZE=5000
PARALLEL_WORKERS=4
```

**Monitor container resources:**
```bash
docker stats mysql-cdc
```

---

## Performance Tuning

### For Small Tables (< 1M rows)
```bash
PARALLEL_WORKERS=4
BATCH_SIZE=5000
```

### For Medium Tables (1M - 10M rows)
```bash
PARALLEL_WORKERS=8
BATCH_SIZE=10000
```

### For Large Tables (10M - 50M rows)
```bash
PARALLEL_WORKERS=16
BATCH_SIZE=50000
```

### For Very Large Tables (> 50M rows)
```bash
PARALLEL_WORKERS=32
BATCH_SIZE=100000

# Also increase MySQL timeouts
# Add to DSN in run-external-db.sh:
# ?maxAllowedPacket=134217728&writeTimeout=60s&readTimeout=60s
```

### Network Optimization

For remote databases, add compression:
```bash
# In run-external-db.sh, modify DSN:
SRC_DSN="...?charset=utf8mb4&compress=true"
TGT_DSN="...?charset=utf8mb4&compress=true"
```

---

## Architecture

### Components

```
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│  Source MySQL   │      │   CDC Container  │      │  Target MySQL   │
│  (Production)   │─────▶│  (Go + Docker)   │─────▶│  (Replica)      │
│                 │      │                  │      │                 │
│  • Binlog ON    │      │  • Full Load     │      │  • Same Schema  │
│  • ROW format   │      │  • CDC Stream    │      │  • Checkpoints  │
└─────────────────┘      │  • UTF-32 Decode │      └─────────────────┘
                         └──────────────────┘
```

### Replication Flow

1. **Initialization**
   - Connect to source and target databases
   - Verify table schema
   - Check for existing checkpoint

2. **Full Load Phase**
   - Copy schema to target
   - Stream rows in batches (ORDER BY PK + LIMIT/OFFSET)
   - Handle composite primary keys
   - Record binlog position

3. **CDC Phase**
   - Subscribe to binlog from recorded position
   - Decode binlog events (INSERT/UPDATE/DELETE)
   - Convert UTF-32 → UTF-8 for charset columns
   - Apply changes to target
   - Write checkpoints periodically

### Data Flow for UTF-32 Columns

```
Source MySQL (UTF-32) → Binlog (144 bytes) → Go Decoder → UTF-8 String (36 chars) → Target MySQL (UTF-32)
                                                 ↓
                                    Detects null bytes (> 25%)
                                                 ↓
                                    Big-endian UTF-32 decode
                                                 ↓
                                    Validates Unicode ranges
```

### Checkpoint Strategy

Checkpoints are written to `cdc_checkpoints` table:

| Column | Type | Description |
|--------|------|-------------|
| `table_key` | VARCHAR(255) | Unique identifier (db.table) |
| `binlog_file` | VARCHAR(255) | Current binlog file |
| `binlog_pos` | BIGINT | Current binlog position |
| `updated_at` | TIMESTAMP | Last update time |

**Write Frequency**: Every 100 events or 5 seconds (whichever comes first)

---

## Deployment on Any Machine

### Option 1: Docker Deployment (Recommended)

**On Ubuntu/Debian:**
```bash
# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
# Logout and login again

# Deploy CDC
cd /path/to/mysql-cdc-go-single-table
chmod +x cdc-control.sh run-external-db.sh
./cdc-control.sh start
```

**On CentOS/RHEL:**
```bash
# Install Docker
sudo yum install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER
# Logout and login again

# Deploy CDC
cd /path/to/mysql-cdc-go-single-table
chmod +x cdc-control.sh run-external-db.sh
./cdc-control.sh start
```

**On macOS:**
```bash
# Install Docker Desktop
# Download from: https://www.docker.com/products/docker-desktop

# Deploy CDC
cd /path/to/mysql-cdc-go-single-table
chmod +x cdc-control.sh run-external-db.sh
./cdc-control.sh start
```

### Option 2: Systemd Service (Native Linux)

Create `/etc/systemd/system/mysql-cdc.service`:
```ini
[Unit]
Description=MySQL CDC Replication Service
After=network.target docker.service
Requires=docker.service

[Service]
Type=simple
User=your_user
WorkingDirectory=/path/to/mysql-cdc-go-single-table
ExecStart=/path/to/mysql-cdc-go-single-table/cdc-control.sh start
ExecStop=/path/to/mysql-cdc-go-single-table/cdc-control.sh stop
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable mysql-cdc
sudo systemctl start mysql-cdc
sudo systemctl status mysql-cdc
```

---

## Monitoring and Maintenance

### Health Checks

```bash
# Quick status
./cdc-control.sh status

# Detailed logs
./cdc-control.sh logs

# Real-time monitoring
./cdc-control.sh follow
```

### Metrics to Monitor

1. **Replication Lag**
```sql
-- Compare max timestamps
SELECT 
  (SELECT MAX(ts) FROM source_db.source_table) as source_max,
  (SELECT MAX(ts) FROM target_db.target_table) as target_max;
```

2. **Row Count Diff**
```sql
SELECT 
  (SELECT COUNT(*) FROM source_db.source_table) as source_count,
  (SELECT COUNT(*) FROM target_db.target_table) as target_count;
```

3. **Container Resources**
```bash
docker stats mysql-cdc --no-stream
```

---

## Command Reference

### cdc-control.sh Commands

| Command | Description |
|---------|-------------|
| `start` | Build image and start CDC container |
| `stop` | Stop and remove CDC container |
| `restart` | Stop, rebuild, and start CDC |
| `status` | Show CDC status and recent activity |
| `logs` | Show last 50 lines of logs |
| `follow` | Follow logs in real-time |
| `build` | Build Docker image only |

### Examples

```bash
# Start CDC
./cdc-control.sh start

# Check if running
./cdc-control.sh status

# Watch logs live
./cdc-control.sh follow

# Stop CDC
./cdc-control.sh stop

# Restart after config change
./cdc-control.sh restart
```

---

## FAQ

**Q: Can I replicate multiple tables?**
A: Currently designed for single table. Deploy multiple instances with different configs.

**Q: Does it support schema changes (DDL)?**
A: No, only DML (INSERT/UPDATE/DELETE). Schema changes must be applied manually to both databases.

**Q: What happens if target database is down?**
A: CDC will retry with exponential backoff. Once target is back, it resumes from last checkpoint.

**Q: Can I replicate to different table name?**
A: Yes, set `SRC_TABLE` and `TGT_TABLE` to different names in `run-external-db.sh`.

**Q: How do I upgrade to new version?**
A: Pull new code, then run `./cdc-control.sh restart`. Checkpoints are preserved.

**Q: Does it handle MySQL restarts?**
A: Yes, binlog file rotation is handled automatically. Just restart CDC after MySQL is back.

---

## Support and Troubleshooting Checklist

Before reporting issues, verify:

- [ ] Source MySQL has binlog enabled (`SHOW VARIABLES LIKE 'log_bin';`)
- [ ] Binlog format is ROW (`SHOW VARIABLES LIKE 'binlog_format';`)
- [ ] User has REPLICATION SLAVE privilege
- [ ] Network connectivity between CDC and both databases
- [ ] Target table schema matches source
- [ ] Sufficient disk space on target database
- [ ] Docker has enough resources allocated

**Get diagnostic info:**
```bash
./cdc-control.sh status > cdc-status.txt
./cdc-control.sh logs > cdc-logs.txt
docker inspect mysql-cdc > cdc-inspect.txt
```

---

## License and Credits

This CDC solution is built with:
- Go 1.21
- github.com/siddontang/go-mysql (binlog parsing)
- github.com/go-sql-driver/mysql (MySQL driver)
- Docker (containerization)

Developed and tested with MySQL 8.0, compatible with MySQL 5.7+.

---

**Version**: 1.0.0  
**Last Updated**: November 2025
=======
# mysql-cdc-go-single-table
>>>>>>> db1a79339a70a5320e483ff7e50d990fac7fe5ef
