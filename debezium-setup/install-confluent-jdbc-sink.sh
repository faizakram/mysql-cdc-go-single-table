#!/bin/bash

# Install Confluent JDBC Sink Connector to Debezium Connect
# This connector has better DELETE support than Debezium JDBC Sink

set -e

echo "=========================================================================="
echo "Installing Confluent JDBC Sink Connector"
echo "=========================================================================="
echo ""

CONNECTOR_VERSION="10.7.4"
CONNECTOR_URL="https://d1i4a15mxbxib1.cloudfront.net/api/plugins/confluentinc/kafka-connect-jdbc/versions/${CONNECTOR_VERSION}/confluentinc-kafka-connect-jdbc-${CONNECTOR_VERSION}.zip"

echo "📥 Downloading Confluent JDBC Sink Connector v${CONNECTOR_VERSION}..."
curl -L -o /tmp/kafka-connect-jdbc.zip "$CONNECTOR_URL"

if [[ $? -ne 0 ]]; then
    echo "❌ Failed to download connector"
    exit 1
fi

echo "✅ Downloaded successfully"
echo ""

echo "📦 Extracting connector..."
unzip -q /tmp/kafka-connect-jdbc.zip -d /tmp/

echo "📤 Copying to Debezium Connect container..."
docker cp /tmp/confluentinc-kafka-connect-jdbc-${CONNECTOR_VERSION}/lib/. debezium-connect:/kafka/connect/kafka-connect-jdbc/

if [[ $? -ne 0 ]]; then
    echo "❌ Failed to copy connector to container"
    exit 1
fi

echo "✅ Connector files copied"
echo ""

echo "🔄 Restarting Debezium Connect..."
docker restart debezium-connect

echo ""
echo "⏳ Waiting for Debezium Connect to restart..."
sleep 20

# Wait for Debezium to be ready
MAX_ATTEMPTS=30
ATTEMPT=0
while [[ $ATTEMPT -lt $MAX_ATTEMPTS ]]; do
    ATTEMPT=$((ATTEMPT + 1))
    
    if curl -s -f http://localhost:8083/ > /dev/null 2>&1; then
        echo "✅ Debezium Connect is ready!"
        break
    fi
    
    if [[ $((ATTEMPT % 5)) -eq 0 ]]; then
        echo "   Still waiting... (attempt $ATTEMPT/$MAX_ATTEMPTS)"
    fi
    
    if [[ $ATTEMPT -eq $MAX_ATTEMPTS ]]; then
        echo "❌ Timeout waiting for Debezium Connect"
        exit 1
    fi
    
    sleep 2
done

echo ""
echo "🔍 Verifying connector installation..."
if curl -s http://localhost:8083/connector-plugins | jq -e '.[] | select(.class == "io.confluent.connect.jdbc.JdbcSinkConnector")' > /dev/null; then
    echo "✅ Confluent JDBC Sink Connector installed successfully!"
else
    echo "❌ Connector not found in plugin list"
    exit 1
fi

echo ""
echo "🧹 Cleaning up..."
rm -rf /tmp/kafka-connect-jdbc.zip /tmp/confluentinc-kafka-connect-jdbc-${CONNECTOR_VERSION}

echo ""
echo "=========================================================================="
echo "✅ Installation Complete!"
echo "=========================================================================="
echo ""
echo "Available JDBC Sink Connectors:"
curl -s http://localhost:8083/connector-plugins | jq '.[] | select(.class | contains("JdbcSink"))'
echo ""
echo "Now you can use: io.confluent.connect.jdbc.JdbcSinkConnector"
echo "This connector supports proper DELETE operations via tombstone records"
echo ""
