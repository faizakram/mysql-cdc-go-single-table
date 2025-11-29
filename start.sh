#!/bin/bash

# MySQL CDC Setup and Start Script

set -e

echo "🚀 MySQL CDC Go Single Table Setup"
echo "=================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to print colored output
print_step() {
    echo -e "${GREEN}➜${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✖${NC} $1"
}

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    print_error "Docker is not running. Please start Docker first."
    exit 1
fi

print_step "Step 1: Building CDC application..."
docker build -t mysql-cdc-go-single-table .

print_step "Step 2: Starting MySQL databases..."
docker compose up -d mysql-source mysql-target

print_step "Waiting for MySQL databases to be ready..."
sleep 10

# Wait for source database
print_step "Checking source database health..."
until docker exec mysql-source mysqladmin ping -h localhost -uroot -prootpass --silent 2>/dev/null; do
    echo -n "."
    sleep 2
done
echo ""
print_step "Source database is ready!"

# Wait for target database
print_step "Checking target database health..."
until docker exec mysql-target mysqladmin ping -h localhost -uroot -prootpass --silent 2>/dev/null; do
    echo -n "."
    sleep 2
done
echo ""
print_step "Target database is ready!"

echo ""
print_step "Verifying source table..."
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
    -e "SELECT COUNT(*) as total_rows FROM channel_transactions;" 2>/dev/null || {
    print_error "Could not verify source table"
}

echo ""
print_step "Step 3: Starting CDC application..."
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

echo ""
print_step "Waiting for CDC to complete initial load..."
sleep 5

echo ""
print_step "✅ Setup complete!"
echo ""
echo "📊 Database Information:"
echo "   Source MySQL: localhost:3306 (user: srcuser, password: srcpass)"
echo "   Target MySQL: localhost:3307 (user: tgtuser, password: tgtpass)"
echo ""
echo "📝 Useful commands:"
echo "   View CDC logs:        docker logs -f mysql-cdc"
echo "   Check source data:    docker exec mysql-source mysql -usrcuser -psrcpass offercraft -e 'SELECT * FROM channel_transactions;'"
echo "   Check target data:    docker exec mysql-target mysql -utgtuser -ptgtpass offercraft -e 'SELECT * FROM channel_transactions_temp;'"
echo "   Stop everything:      docker stop mysql-cdc && docker-compose down"
echo ""
echo "🧪 Test CDC with:"
echo "   Insert: docker exec mysql-source mysql -usrcuser -psrcpass offercraft -e \"INSERT INTO channel_transactions (transaction_id, channel_name, amount) VALUES ('TXN-999', 'test', 99.99);\""
echo ""
print_warning "Viewing CDC logs in 3 seconds..."
sleep 3
docker logs -f mysql-cdc
