#!/bin/bash

# Test script to verify CDC captures changes during full load

echo "╔═════════════════════════════════════════════════════════╗"
echo "║  Testing CDC Capture During Full Load                  ║"
echo "╚═════════════════════════════════════════════════════════╝"
echo ""

# Start CDC in background
echo "1️⃣  Starting CDC with full load..."
./run-external-db.sh &
CDC_PID=$!
echo "   CDC started with PID: $CDC_PID"
echo ""

# Wait for full load to start
echo "2️⃣  Waiting for full load to begin..."
sleep 3

# Check if CDC is running
if ! docker ps | grep -q mysql-cdc; then
    echo "❌ CDC container not running!"
    exit 1
fi
echo "   ✅ CDC container is running"
echo ""

# Monitor until we see "Streaming load progress"
echo "3️⃣  Waiting for streaming load to start..."
for i in {1..30}; do
    if docker logs mysql-cdc 2>&1 | grep -q "Streaming load progress"; then
        echo "   ✅ Streaming load started"
        break
    fi
    sleep 1
done
echo ""

# Insert test records into source during full load
echo "4️⃣  Inserting test records during full load..."
echo "   This simulates real-time changes happening during initial sync"

# Get a sample record to use as template
SAMPLE=$(mysql -h 127.0.0.1 -P 3306 -uroot -pNewStrongPassword123 guardian -N -e "
    SELECT CONCAT(
        'INSERT INTO channel_txn_temp (unique_id, order_id, channel_id, created_at) VALUES (',
        QUOTE(CONCAT('TEST-CDC-', UUID())), ',',
        QUOTE(CONCAT('ORDER-', UUID())), ',',
        '999,',
        QUOTE(NOW()),
        ');'
    ) FROM channel_txn_temp LIMIT 1;" 2>/dev/null)

# Insert 5 test records
for i in {1..5}; do
    TEST_ID="TEST-CDC-DURING-LOAD-$i-$(date +%s)"
    mysql -h 127.0.0.1 -P 3306 -uroot -pNewStrongPassword123 guardian -e "
        INSERT INTO channel_txn_temp (unique_id, order_id, channel_id, created_at, updated_at) 
        VALUES ('$TEST_ID', 'ORDER-TEST-$i', 999, NOW(), NOW());
    " 2>/dev/null
    echo "   Inserted test record: $TEST_ID"
    sleep 1
done
echo ""

# Update one existing record
echo "5️⃣  Testing UPDATE during full load..."
mysql -h 127.0.0.1 -P 3306 -uroot -pNewStrongPassword123 guardian -e "
    UPDATE channel_txn_temp 
    SET unique_id = CONCAT('UPDATED-', unique_id), updated_at = NOW() 
    WHERE channel_id = 1 
    LIMIT 1;
" 2>/dev/null
echo "   ✅ Updated 1 record"
echo ""

# Wait for full load to complete
echo "6️⃣  Waiting for full load to complete..."
for i in {1..300}; do
    if docker logs mysql-cdc 2>&1 | grep -q "Streaming load completed"; then
        echo "   ✅ Full load completed!"
        break
    fi
    if docker logs mysql-cdc 2>&1 | grep -q "Starting CDC from"; then
        echo "   ✅ CDC started!"
        break
    fi
    sleep 2
    if [ $((i % 15)) -eq 0 ]; then
        PROGRESS=$(docker logs mysql-cdc 2>&1 | grep "Streaming load progress" | tail -1)
        if [ ! -z "$PROGRESS" ]; then
            echo "   Progress: $PROGRESS"
        fi
    fi
done
echo ""

# Wait a bit for CDC to catch up
echo "7️⃣  Waiting for CDC to process events..."
sleep 5
echo ""

# Verify test records in target
echo "8️⃣  Verifying test records were captured by CDC..."
TEST_COUNT=$(mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword guardian -N -e "
    SELECT COUNT(*) FROM channel_txn_temp WHERE unique_id LIKE 'TEST-CDC-DURING-LOAD-%';
" 2>/dev/null)

echo "   Found $TEST_COUNT test records in target database"

if [ "$TEST_COUNT" -ge "5" ]; then
    echo "   ✅ SUCCESS: CDC captured records inserted during full load!"
else
    echo "   ❌ FAIL: Expected 5+ test records, found $TEST_COUNT"
fi
echo ""

# Verify UPDATE was captured
UPDATE_COUNT=$(mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword guardian -N -e "
    SELECT COUNT(*) FROM channel_txn_temp WHERE unique_id LIKE 'UPDATED-%';
" 2>/dev/null)

echo "   Found $UPDATE_COUNT updated records in target"
if [ "$UPDATE_COUNT" -ge "1" ]; then
    echo "   ✅ SUCCESS: CDC captured UPDATE during full load!"
else
    echo "   ❌ FAIL: UPDATE not captured"
fi
echo ""

# Compare total counts
SOURCE_COUNT=$(mysql -h 127.0.0.1 -P 3306 -uroot -pNewStrongPassword123 guardian -N -e "
    SELECT COUNT(*) FROM channel_txn_temp;" 2>/dev/null)
TARGET_COUNT=$(mysql -h 127.0.0.1 -P 3307 -uroot -ptargetpassword guardian -N -e "
    SELECT COUNT(*) FROM channel_txn_temp;" 2>/dev/null)

echo "9️⃣  Final count verification:"
echo "   Source: $SOURCE_COUNT rows"
echo "   Target: $TARGET_COUNT rows"
echo "   Difference: $((SOURCE_COUNT - TARGET_COUNT)) rows"

if [ "$SOURCE_COUNT" -eq "$TARGET_COUNT" ]; then
    echo "   ✅ SUCCESS: Row counts match perfectly!"
else
    echo "   ⚠️  Row count mismatch (may need more time for CDC to catch up)"
fi
echo ""

echo "═══════════════════════════════════════════════════════════"
echo "Test completed! CDC container is still running."
echo "Check logs: docker logs -f mysql-cdc"
echo "Stop CDC: docker stop mysql-cdc"
echo "═══════════════════════════════════════════════════════════"
