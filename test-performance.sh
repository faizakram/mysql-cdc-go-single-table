#!/bin/bash
# Quick performance test

echo "========================================="
echo " Performance Test"
echo "========================================="
echo ""

# Stop any running CDC
./cdc-control.sh stop 2>/dev/null

# Clear target
mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword guardian -e "
DROP TABLE IF EXISTS channel_txn_temp;
DROP TABLE IF EXISTS cdc_checkpoints;
DROP TABLE IF EXISTS full_load_progress;
" 2>/dev/null

# Count source rows
echo "Source database:"
SOURCE_COUNT=$(mysql -h 127.0.0.1 -P 3306 -uroot -pNewStrongPassword123 guardian -e "SELECT COUNT(*) as count FROM channel_txn_temp" 2>/dev/null | tail -1)
echo "  Total rows: $SOURCE_COUNT"
echo ""

# Start timing
echo "Starting full load test..."
START_TIME=$(date +%s)

# Start CDC (will do full load)
./cdc-control.sh start > /tmp/cdc-perf-test.log 2>&1 &
CDC_PID=$!

# Wait for it to start
sleep 5

# Monitor progress
echo "Monitoring progress (Ctrl+C when done)..."
echo ""
tail -f /tmp/cdc-perf-test.log | grep --line-buffered "progress\|completed" &
TAIL_PID=$!

# Wait for completion
wait $CDC_PID

# Stop monitoring
kill $TAIL_PID 2>/dev/null

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

# Count target rows
TARGET_COUNT=$(mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword guardian -e "SELECT COUNT(*) as count FROM channel_txn_temp" 2>/dev/null | tail -1)

echo ""
echo "========================================="
echo " Results"
echo "========================================="
echo "Source rows:  $SOURCE_COUNT"
echo "Target rows:  $TARGET_COUNT"
echo "Duration:     $DURATION seconds"
echo "Speed:        $((SOURCE_COUNT / DURATION)) rows/second"
echo ""

# Calculate 15M estimate
ESTIMATE=$((15000000 / (SOURCE_COUNT / DURATION)))
echo "Estimated time for 15M records: $((ESTIMATE / 60)) minutes"
echo "========================================="

