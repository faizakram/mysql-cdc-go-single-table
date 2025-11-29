#!/bin/bash

# CDC Test Script

echo "🧪 MySQL CDC Testing"
echo "===================="
echo ""

# Test 1: Check data sync
echo "Test 1: Checking initial data sync..."
SRC_COUNT=$(docker exec mysql-source mysql -usrcuser -psrcpass offercraft -se "SELECT COUNT(*) FROM channel_transactions;" 2>/dev/null)
TGT_COUNT=$(docker exec mysql-target mysql -utgtuser -ptgtpass offercraft -se "SELECT COUNT(*) FROM channel_transactions_temp;" 2>/dev/null)
echo "Source count: $SRC_COUNT"
echo "Target count: $TGT_COUNT"
if [ "$SRC_COUNT" == "$TGT_COUNT" ]; then
    echo "✅ Counts match!"
else
    echo "❌ Counts don't match!"
fi
echo ""

# Test 2: INSERT
echo "Test 2: Testing INSERT..."
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "INSERT INTO channel_transactions (transaction_id, channel_name, amount, currency, status) 
      VALUES ('TEST-INSERT', 'api', 500.00, 'USD', 'pending');" 2>/dev/null
sleep 2
RESULT=$(docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -se "SELECT transaction_id FROM channel_transactions_temp WHERE transaction_id='TEST-INSERT';" 2>/dev/null)
if [ "$RESULT" == "TEST-INSERT" ]; then
    echo "✅ INSERT replicated successfully!"
else
    echo "❌ INSERT not found in target!"
fi
echo ""

# Test 3: UPDATE
echo "Test 3: Testing UPDATE..."
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "UPDATE channel_transactions SET status='completed', amount=600.00 WHERE transaction_id='TEST-INSERT';" 2>/dev/null
sleep 2
AMOUNT=$(docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -se "SELECT amount FROM channel_transactions_temp WHERE transaction_id='TEST-INSERT';" 2>/dev/null)
if [ "$AMOUNT" == "600.00" ]; then
    echo "✅ UPDATE replicated successfully!"
else
    echo "❌ UPDATE not reflected! Amount: $AMOUNT"
fi
echo ""

# Test 4: DELETE
echo "Test 4: Testing DELETE..."
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "DELETE FROM channel_transactions WHERE transaction_id='TEST-INSERT';" 2>/dev/null
sleep 2
RESULT=$(docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -se "SELECT COUNT(*) FROM channel_transactions_temp WHERE transaction_id='TEST-INSERT';" 2>/dev/null)
if [ "$RESULT" == "0" ]; then
    echo "✅ DELETE replicated successfully!"
else
    echo "❌ DELETE not replicated!"
fi
echo ""

# Test 5: Checkpoint
echo "Test 5: Checking checkpoint..."
CHECKPOINT=$(docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -se "SELECT binlog_file, binlog_pos FROM cdc_checkpoints LIMIT 1;" 2>/dev/null)
if [ -n "$CHECKPOINT" ]; then
    echo "✅ Checkpoint found: $CHECKPOINT"
else
    echo "❌ No checkpoint found!"
fi
echo ""

# Final count
echo "📊 Final Statistics:"
echo "-------------------"
SRC_FINAL=$(docker exec mysql-source mysql -usrcuser -psrcpass offercraft -se "SELECT COUNT(*) FROM channel_transactions;" 2>/dev/null)
TGT_FINAL=$(docker exec mysql-target mysql -utgtuser -ptgtpass offercraft -se "SELECT COUNT(*) FROM channel_transactions_temp;" 2>/dev/null)
echo "Source total: $SRC_FINAL"
echo "Target total: $TGT_FINAL"
if [ "$SRC_FINAL" == "$TGT_FINAL" ]; then
    echo "✅ All tests passed!"
else
    echo "⚠️  Counts differ: Source=$SRC_FINAL, Target=$TGT_FINAL"
fi
