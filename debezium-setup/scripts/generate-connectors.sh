#!/bin/bash

# Script to generate connector configurations from .env file
# This ensures all configurations are centralized

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"
CONNECTORS_DIR="$PROJECT_DIR/connectors"

# Load environment variables
if [ -f "$ENV_FILE" ]; then
    source "$ENV_FILE"
else
    echo "ERROR: .env file not found at $ENV_FILE"
    exit 1
fi

echo "Generating connector configurations from .env..."

# Generate MS SQL Source Connector Configuration
cat > "$CONNECTORS_DIR/mssql-source.json" <<EOF
{
  "name": "${SOURCE_CONNECTOR_NAME}",
  "config": {
    "connector.class": "io.debezium.connector.sqlserver.SqlServerConnector",
    "tasks.max": "${CONNECTOR_TASKS_MAX}",
    
    "database.hostname": "${MSSQL_HOST}",
    "database.port": "${MSSQL_PORT}",
    "database.user": "${MSSQL_USER}",
    "database.password": "${MSSQL_PASSWORD}",
    "database.names": "${MSSQL_DATABASE}",
    "database.encrypt": "false",
    
    "topic.prefix": "${TOPIC_PREFIX}",
    "table.include.list": "${TABLE_INCLUDE_LIST}",
    
    "schema.history.internal.kafka.bootstrap.servers": "${KAFKA_BOOTSTRAP_SERVERS}",
    "schema.history.internal.kafka.topic": "schema-changes.${TOPIC_PREFIX}",
    
    "snapshot.mode": "${SNAPSHOT_MODE}",
    "snapshot.isolation.mode": "${SNAPSHOT_ISOLATION_MODE}",
    
    "decimal.handling.mode": "precise",
    "time.precision.mode": "adaptive_time_microseconds",
    
    "tombstones.on.delete": "true",
    
    "transforms": "route",
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "([^.]+)\\\\.([^.]+)\\\\.([^.]+)",
    "transforms.route.replacement": "\$3"
  }
}
EOF

echo "✅ Generated: $CONNECTORS_DIR/mssql-source.json"

# Generate PostgreSQL Sink Connector Configuration
cat > "$CONNECTORS_DIR/postgres-sink.json" <<EOF
{
  "name": "${SINK_CONNECTOR_NAME}",
  "config": {
    "connector.class": "io.debezium.connector.jdbc.JdbcSinkConnector",
    "tasks.max": "${CONNECTOR_TASKS_MAX}",
    
    "connection.url": "jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}?currentSchema=${POSTGRES_SCHEMA}",
    "connection.username": "${POSTGRES_USER}",
    "connection.password": "${POSTGRES_PASSWORD}",
    
    "topics.regex": "${TOPIC_PREFIX}\\\\.${MSSQL_DATABASE}\\\\.${MSSQL_SCHEMA}\\\\.(.*)",
    
    "transforms": "route,unwrap,renameDeleted,castDeleted,snakeCaseKey,snakeCaseValue,typeConversion",
    
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "${TOPIC_PREFIX}\\\\.${MSSQL_DATABASE}\\\\.${MSSQL_SCHEMA}\\\\.(.*)",
    "transforms.route.replacement": "\$1",
    
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.delete.handling.mode": "rewrite",
    
    "transforms.renameDeleted.type": "org.apache.kafka.connect.transforms.ReplaceField\$Value",
    "transforms.renameDeleted.renames": "__deleted:__cdc_deleted",
    
    "transforms.castDeleted.type": "org.apache.kafka.connect.transforms.Cast\$Value",
    "transforms.castDeleted.spec": "__cdc_deleted:boolean",
    
    "transforms.snakeCaseKey.type": "com.debezium.transforms.SnakeCaseTransform\$Key",
    "transforms.snakeCaseValue.type": "com.debezium.transforms.SnakeCaseTransform\$Value",
    
    "transforms.typeConversion.type": "com.debezium.transforms.TypeConversionTransform",
    "transforms.typeConversion.uuid.columns": "${UUID_COLUMNS}",
    "transforms.typeConversion.json.columns": "${JSON_COLUMNS}",
    
    "table.name.format": "${POSTGRES_SCHEMA}.\${topic}",
    "insert.mode": "${INSERT_MODE}",
    "delete.enabled": "${DELETE_ENABLED}",
    "primary.key.mode": "record_key",
    
    "schema.evolution": "${SCHEMA_EVOLUTION}",
    "quote.identifiers": "false"
  }
}
EOF

echo "✅ Generated: $CONNECTORS_DIR/postgres-sink.json"

echo ""
echo "✅ All connector configurations generated successfully!"
echo ""
echo "Configuration Summary:"
echo "  Source: ${MSSQL_HOST}:${MSSQL_PORT}/${MSSQL_DATABASE}"
echo "  Target: ${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}"
echo "  Topic Prefix: ${TOPIC_PREFIX}"
echo ""
