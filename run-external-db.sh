#!/bin/bash

# MySQL CDC Runner for External Databases
# This script runs the CDC container with your existing database endpoints

# ============================================
# CONFIGURE YOUR DATABASE DETAILS HERE
# ============================================

# Source Database (where data comes from)
SRC_HOST="localhost"
SRC_PORT="3306"
SRC_USER="root"
SRC_PASS="NewStrongPassword123"
SRC_DATABASE="guardian"
SRC_TABLE="channel_txn_temp"

# Target Database (where data goes to)
TGT_HOST="localhost"
TGT_PORT="3307"
TGT_USER="root"
TGT_PASS="targetpassword"
TGT_DATABASE="guardian"
TGT_TABLE="channel_txn_temp"

# CDC Configuration
PARALLEL_WORKERS="8"        # Number of parallel workers (increase for faster load)
BATCH_SIZE="10000"         # Rows per batch (increase for better throughput)
SERVER_ID="9999"           # Unique binlog server ID
CHECKPOINT_TABLE="_cdc_checkpoint"
CHECKPOINT_INTERVAL="10"   # Checkpoint write interval in seconds

# ============================================
# DO NOT MODIFY BELOW THIS LINE
# ============================================

# If --dry-run flag is passed, just export variables and exit
if [ "$1" = "--dry-run" ]; then
    export SRC_HOST SRC_PORT SRC_USER SRC_PASS SRC_DATABASE SRC_TABLE
    export TGT_HOST TGT_PORT TGT_USER TGT_PASS TGT_DATABASE TGT_TABLE
    export PARALLEL_WORKERS BATCH_SIZE SERVER_ID CHECKPOINT_TABLE CHECKPOINT_INTERVAL
    exit 0
fi

# Build DSN strings
SRC_DSN="${SRC_USER}:${SRC_PASS}@tcp(${SRC_HOST}:${SRC_PORT})/?maxAllowedPacket=67108864&readTimeout=30s"
TGT_DSN="${TGT_USER}:${TGT_PASS}@tcp(${TGT_HOST}:${TGT_PORT})/?maxAllowedPacket=67108864&writeTimeout=30s"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║         MySQL CDC - External Database Runner            ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Source: ${SRC_USER}@${SRC_HOST}:${SRC_PORT}/${SRC_DATABASE}.${SRC_TABLE}"
echo "  Target: ${TGT_USER}@${TGT_HOST}:${TGT_PORT}/${TGT_DATABASE}.${TGT_TABLE}"
echo "  Workers: ${PARALLEL_WORKERS}"
echo "  Batch Size: ${BATCH_SIZE}"
echo ""

# Check if Docker image exists
if ! docker image inspect mysql-cdc-go-single-table >/dev/null 2>&1; then
    echo "❌ Docker image 'mysql-cdc-go-single-table' not found!"
    echo "   Building image..."
    docker build -t mysql-cdc-go-single-table . || {
        echo "❌ Failed to build Docker image"
        exit 1
    }
    echo "✅ Image built successfully"
fi

# Stop existing container if running
if docker ps -a --format '{{.Names}}' | grep -q "^mysql-cdc$"; then
    echo "⚠️  Stopping existing mysql-cdc container..."
    docker stop mysql-cdc >/dev/null 2>&1
    docker rm mysql-cdc >/dev/null 2>&1
fi

echo ""
echo "🚀 Starting CDC container..."
echo ""

# Run the container
docker run -d \
  --name mysql-cdc \
  --network host \
  -e SRC_DSN="${SRC_DSN}" \
  -e TGT_DSN="${TGT_DSN}" \
  -e SRC_DB="${SRC_DATABASE}" \
  -e TGT_DB="${TGT_DATABASE}" \
  -e SRC_TABLE="${SRC_TABLE}" \
  -e TARGET_TABLE="${TGT_TABLE}" \
  -e BINLOG_SERVER_ID="${SERVER_ID}" \
  -e PARALLEL_WORKERS="${PARALLEL_WORKERS}" \
  -e BATCH_SIZE="${BATCH_SIZE}" \
  -e CHECKPOINT_TABLE="${CHECKPOINT_TABLE}" \
  -e CHECKPOINT_WRITE_SECONDS="${CHECKPOINT_INTERVAL}" \
  mysql-cdc-go-single-table

if [ $? -eq 0 ]; then
    echo "✅ CDC container started successfully!"
    echo ""
    echo "📊 Monitoring logs (Ctrl+C to exit, container keeps running)..."
    echo ""
    sleep 2
    docker logs -f mysql-cdc
else
    echo "❌ Failed to start CDC container"
    exit 1
fi
