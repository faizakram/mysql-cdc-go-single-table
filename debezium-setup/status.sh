#!/bin/bash

# Check status of all Debezium connectors

DEBEZIUM_URL="http://localhost:8083"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║              Debezium Connector Status                     ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Check if Debezium is running
if ! curl -s $DEBEZIUM_URL > /dev/null 2>&1; then
    echo "❌ Debezium Connect is not running!"
    echo "   Run: docker-compose up -d"
    exit 1
fi

# List all connectors
echo "📋 Active Connectors:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
curl -s $DEBEZIUM_URL/connectors | jq -r '.[]' | while read connector; do
    echo "  • $connector"
done
echo ""

# Get detailed status for each connector
echo "📊 Connector Details:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

curl -s $DEBEZIUM_URL/connectors | jq -r '.[]' | while read connector; do
    echo ""
    echo "Connector: $connector"
    
    STATUS=$(curl -s $DEBEZIUM_URL/connectors/$connector/status)
    
    STATE=$(echo "$STATUS" | jq -r '.connector.state')
    WORKER=$(echo "$STATUS" | jq -r '.connector.worker_id')
    
    if [ "$STATE" = "RUNNING" ]; then
        echo "  ✅ State: $STATE"
    else
        echo "  ❌ State: $STATE"
    fi
    
    echo "  Worker: $WORKER"
    
    # Task status
    TASKS=$(echo "$STATUS" | jq -r '.tasks[] | "Task \(.id): \(.state)"')
    echo "  Tasks:"
    echo "$TASKS" | sed 's/^/    /'
    
    # Error check
    ERROR=$(echo "$STATUS" | jq -r '.connector.trace // empty')
    if [ ! -z "$ERROR" ]; then
        echo "  ⚠️  Error: $ERROR"
    fi
    
    echo "  ───────────────────────────────────────────────────────────"
done

echo ""
echo "🔗 Web UIs:"
echo "  Kafka UI:     http://localhost:8080"
echo "  Debezium API: http://localhost:8083"
echo ""

# Show topic count
echo "📦 Kafka Topics (one per table):"
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | wc -l | xargs echo "  Total topics:"
echo ""

# Show recent messages
echo "📨 Recent Activity:"
LATEST_TOPIC=$(docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | tail -1)
if [ ! -z "$LATEST_TOPIC" ]; then
    echo "  Latest topic: $LATEST_TOPIC"
    MESSAGE_COUNT=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic $LATEST_TOPIC 2>/dev/null | awk -F ":" '{sum += $3} END {print sum}')
    echo "  Messages: $MESSAGE_COUNT"
fi
