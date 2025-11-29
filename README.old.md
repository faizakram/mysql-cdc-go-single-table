# MySQL CDC Go Single Table

A Go-based Change Data Capture (CDC) solution for replicating a single MySQL table using binlog streaming.

## Features

- Full table load with parallel workers for tables with integer primary keys
- Continuous CDC using MySQL binlog replication
- Automatic checkpoint management
- Retry logic with exponential backoff
- Support for INSERT, UPDATE, and DELETE operations

## Prerequisites

- Docker and Docker Compose
- Go 1.21+ (for development)

## Quick Start with Docker Compose

### 1. Start MySQL Databases

```bash
# Start source and target MySQL databases
docker compose up -d mysql-source mysql-target

# Wait for databases to be healthy (about 30 seconds)
docker compose ps
```

This will create:
- **Source MySQL**: localhost:3306 (user: srcuser, password: srcpass)
- **Target MySQL**: localhost:3307 (user: tgtuser, password: tgtpass)

Both databases have:
- Database name: `offercraft`
- Root password: `rootpass`
- Sample table: `channel_transactions` (source only, with sample data)

### 2. Build the CDC Application

```bash
docker build -t mysql-cdc-go-single-table .
```

### 3. Run the CDC Application

#### Option A: Using Docker Compose (recommended)

Uncomment the `mysql-cdc` service in `docker-compose.yml` and run:

```bash
docker compose up -d mysql-cdc
docker compose logs -f mysql-cdc
```

#### Option B: Using Docker Run

```bash
docker run -d --name mysql-cdc \
  --network mysql-cdc-go-single-table_default \
  -e SRC_DSN='srcuser:srcpass@tcp(mysql-source:3306)/' \
  -e TGT_DSN='tgtuser:tgtpass@tcp(mysql-target:3306)/' \
  -e SRC_DB='offercraft' \
  -e TGT_DB='offercraft' \
  -e SRC_TABLE='channel_transactions' \
  -e TARGET_TABLE='channel_transactions_temp' \
  -e PARALLEL_WORKERS='4' \
  -e BATCH_SIZE='5000' \
  -e DB_RETRY_ATTEMPTS='5' \
  -e DB_RETRY_MAX_WAIT='10' \
  -e FULLLOAD_MAX_RETRIES='3' \
  -e FULLLOAD_DROP_ON_RETRY='true' \
  -e CHECKPOINT_TABLE='cdc_checkpoints' \
  -e BINLOG_SERVER_ID='9999' \
  mysql-cdc-go-single-table

# View logs
docker logs -f mysql-cdc
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SRC_DSN` | `root:rootpass@tcp(source-host:3306)/` | Source MySQL DSN |
| `TGT_DSN` | `root:rootpass@tcp(target-host:3306)/` | Target MySQL DSN |
| `SRC_DB` | `offercraft` | Source database name |
| `TGT_DB` | `offercraft` | Target database name |
| `SRC_TABLE` | `channel_transactions` | Source table name |
| `TARGET_TABLE` | `channel_transactions_temp` | Target table name |
| `PARALLEL_WORKERS` | `4` | Number of parallel workers for full load |
| `BATCH_SIZE` | `5000` | Batch size for data loading |
| `DB_RETRY_ATTEMPTS` | `5` | Number of retry attempts for DB operations |
| `DB_RETRY_MAX_WAIT` | `10` | Max wait time (seconds) between retries |
| `FULLLOAD_MAX_RETRIES` | `3` | Max retries for full load |
| `FULLLOAD_DROP_ON_RETRY` | `true` | Drop target table on retry |
| `CHECKPOINT_TABLE` | `cdc_checkpoints` | Checkpoint table name |
| `CHECKPOINT_EVERY` | `100` | Write checkpoint every N events |
| `CHECKPOINT_WRITE_SECONDS` | `5` | Checkpoint write interval (seconds) |
| `BINLOG_SERVER_ID` | `9999` | Binlog server ID for CDC |

## Testing CDC

### 1. Verify Initial Load

```bash
# Check source table
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "SELECT COUNT(*) FROM channel_transactions;"

# Check target table
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "SELECT COUNT(*) FROM channel_transactions_temp;"
```

### 2. Test INSERT

```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "INSERT INTO channel_transactions (transaction_id, channel_name, amount, currency, status) 
      VALUES ('TXN-006', 'web', 300.00, 'USD', 'completed');"
```

### 3. Test UPDATE

```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "UPDATE channel_transactions SET status='completed', amount=100.00 
      WHERE transaction_id='TXN-002';"
```

### 4. Test DELETE

```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "DELETE FROM channel_transactions WHERE transaction_id='TXN-004';"
```

### 5. Monitor Changes

```bash
# Watch CDC logs
docker logs -f mysql-cdc

# Query target table
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "SELECT * FROM channel_transactions_temp ORDER BY id;"
```

## Architecture

1. **Full Load Phase**:
   - Copies table schema to target
   - Detects if table has single integer primary key
   - Uses parallel workers for efficient bulk loading
   - Captures binlog position after load completes

2. **CDC Phase**:
   - Connects to MySQL binlog stream
   - Filters events for the specified table
   - Applies INSERT/UPDATE/DELETE operations to target
   - Periodically writes checkpoints for recovery

## Cleanup

```bash
# Stop all services
docker compose down

# Remove volumes (deletes all data)
docker compose down -v

# Remove CDC container (if run separately)
docker rm -f mysql-cdc
```

## Troubleshooting

### Check MySQL binlog is enabled

```bash
docker exec mysql-source mysql -uroot -prootpass \
  -e "SHOW VARIABLES LIKE 'log_bin';"
```

### Check binlog format

```bash
docker exec mysql-source mysql -uroot -prootpass \
  -e "SHOW VARIABLES LIKE 'binlog_format';"
```

### View checkpoints

```bash
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "SELECT * FROM cdc_checkpoints;"
```

### Reset and restart

```bash
# Stop CDC
docker-compose stop mysql-cdc

# Clear target table
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "DROP TABLE IF EXISTS channel_transactions_temp; 
      DROP TABLE IF EXISTS cdc_checkpoints; 
      DROP TABLE IF EXISTS full_load_progress;"

# Restart CDC
docker-compose up -d mysql-cdc
```

## Development

### Build locally

```bash
go mod download
go build -o mysql-cdc ./src
```

### Run locally

```bash
export SRC_DSN='srcuser:srcpass@tcp(localhost:3306)/'
export TGT_DSN='tgtuser:tgtpass@tcp(localhost:3307)/'
# ... set other env vars ...
./mysql-cdc
```

## License

MIT
