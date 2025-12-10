#!/bin/bash

# ============================================================================
# Install Confluent JDBC Sink Connector (Community Edition)
# This connector has better schema evolution and type mapping support
# ============================================================================

set -e

CONFLUENT_VERSION="7.5.0"
CONNECTOR_URL="https://d1i4a15mxbxib1.cloudfront.net/api/plugins/confluentinc/kafka-connect-jdbc/versions/${CONFLUENT_VERSION}/confluentinc-kafka-connect-jdbc-${CONFLUENT_VERSION}.zip"

echo "=========================================================================="
echo "Downloading Confluent JDBC Sink Connector ${CONFLUENT_VERSION}"
echo "=========================================================================="

cd /tmp
rm -f confluentinc-kafka-connect-jdbc-*.zip
wget -q "${CONNECTOR_URL}" -O kafka-connect-jdbc.zip

if [ ! -f kafka-connect-jdbc.zip ]; then
    echo "❌ Failed to download connector"
    exit 1
fi

echo "✅ Downloaded successfully"
echo ""
echo "=========================================================================="
echo "Installing to Debezium Connect"
echo "=========================================================================="

# Extract and copy to container
unzip -q kafka-connect-jdbc.zip
EXTRACTED_DIR=$(ls -d confluentinc-kafka-connect-jdbc-*/ | head -1)

if [ -z "$EXTRACTED_DIR" ]; then
    echo "❌ Failed to extract connector"
    exit 1
fi

# Copy to container
docker exec debezium-connect mkdir -p /kafka/connect/confluent-jdbc-sink
docker cp "${EXTRACTED_DIR}lib/." debezium-connect:/kafka/connect/confluent-jdbc-sink/

echo "✅ Connector installed"
echo ""
echo "=========================================================================="
echo "Restarting Debezium Connect"
echo "=========================================================================="

cd /home/faizakram/Downloads/mysql-cdc-go-single-table/debezium-setup
docker compose restart debezium-connect

echo "⏳ Waiting for Connect to be ready..."
sleep 15

# Wait for Connect API
until curl -s http://localhost:8083/ > /dev/null 2>&1; do
    echo "Waiting for Debezium Connect..."
    sleep 2
done

echo "✅ Connect is ready"
echo ""
echo "=========================================================================="
echo "Verifying Confluent JDBC Sink Connector"
echo "=========================================================================="

curl -s http://localhost:8083/connector-plugins | jq '.[] | select(.class | contains("JdbcSinkConnector"))'

echo ""
echo "=========================================================================="
echo "✅ Confluent JDBC Sink Connector installed successfully!"
echo "=========================================================================="
echo ""
echo "This connector supports:"
echo "  - Automatic schema evolution"
echo "  - VARCHAR length preservation"
echo "  - Better type mapping"
echo ""
echo "Update your connector configuration to use:"
echo '  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector"'
