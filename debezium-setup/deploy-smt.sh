#!/bin/bash

# Build and deploy custom Snake Case SMT to Debezium Connect
# This script compiles the Java SMT and deploys it to the running Debezium container

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SMT_DIR="$SCRIPT_DIR/custom-smt"

echo "=========================================================================="
echo "Building Snake Case Transform SMT"
echo "=========================================================================="
echo ""

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is not installed. Installing Maven..."
    sudo apt-get update && sudo apt-get install -y maven
fi

# Build the SMT
echo "📦 Building JAR..."
cd "$SMT_DIR"
mvn clean package -DskipTests

JAR_FILE="$SMT_DIR/target/snake-case-transform-1.0.0.jar"

if [[ ! -f "$JAR_FILE" ]]; then
    echo "❌ Build failed! JAR not found at $JAR_FILE"
    exit 1
fi

echo "✅ Build successful: $JAR_FILE"
echo ""

# Deploy to Debezium Connect
echo "=========================================================================="
echo "Deploying to Debezium Connect"
echo "=========================================================================="
echo ""

# Copy JAR to Debezium Connect container
echo "📤 Copying JAR to Debezium Connect container..."
docker cp "$JAR_FILE" debezium-connect:/kafka/connect/snake-case-transform-1.0.0.jar

if [[ $? -eq 0 ]]; then
    echo "✅ JAR copied successfully"
else
    echo "❌ Failed to copy JAR to container"
    exit 1
fi

echo ""
echo "=========================================================================="
echo "Restarting Debezium Connect"
echo "=========================================================================="
echo ""

cd "$SCRIPT_DIR"
docker compose restart debezium-connect

echo ""
echo "⏳ Waiting for Debezium Connect to be ready..."
sleep 15

# Wait for Debezium to be healthy
for i in {1..30}; do
    if curl -s http://localhost:8083/ > /dev/null 2>&1; then
        echo "✅ Debezium Connect is ready!"
        break
    fi
    if [[ $i -eq 30 ]]; then
        echo "❌ Timeout waiting for Debezium Connect"
        exit 1
    fi
    sleep 2
done

echo ""
echo "=========================================================================="
echo "✅ Snake Case SMT deployed successfully!"
echo "=========================================================================="
echo ""
echo "Now update your connector configuration to use it:"
echo ""
echo '  "transforms": "route,snakeCase",'
echo '  "transforms.snakeCase.type": "com.debezium.transforms.SnakeCaseTransform$Value"'
echo ""
echo "Then redeploy the connector:"
echo "  curl -X DELETE http://localhost:8083/connectors/postgres-sink-connector"
echo "  curl -X POST -H \"Content-Type: application/json\" \\"
echo "    --data @connectors/postgres-sink.json \\"
echo "    http://localhost:8083/connectors"
echo ""
