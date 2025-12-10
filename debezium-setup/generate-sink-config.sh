#!/bin/bash

# Generate postgres-sink.json with snake_case table name mappings

# Function to convert PascalCase to snake_case
to_snake_case() {
    echo "$1" | sed 's/\([a-z0-9]\)\([A-Z]\)/\1_\2/g' | sed 's/\([A-Z]\+\)\([A-Z][a-z]\)/\1_\2/g' | tr '[:upper:]' '[:lower:]'
}

# Get list of topics
TOPICS=$(docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list | grep "mssql.mig_test_db.dbo" | sed 's/mssql\.mig_test_db\.dbo\.//')

echo "Detected tables:"
for topic in $TOPICS; do
    snake=$(to_snake_case "$topic")
    echo "  $topic -> $snake"
done

# Create topic-to-table mapping
TOPIC_MAPPINGS=""
for topic in $TOPICS; do
    snake=$(to_snake_case "$topic")
    if [ -n "$TOPIC_MAPPINGS" ]; then
        TOPIC_MAPPINGS="$TOPIC_MAPPINGS,"
    fi
    TOPIC_MAPPINGS="$TOPIC_MAPPINGS
        \"$topic\": \"dbo.$snake\""
done

# Generate connector config
cat > connectors/postgres-sink-generated.json <<EOF
{
  "name": "postgres-sink-connector",
  "config": {
    "connector.class": "io.debezium.connector.jdbc.JdbcSinkConnector",
    "tasks.max": "10",
    
    "connection.url": "jdbc:postgresql://postgres18:5432/target_db?currentSchema=dbo",
    "connection.username": "admin",
    "connection.password": "admin123",
    
    "topics.regex": "mssql\\\\.mig_test_db\\\\.dbo\\\\.(.*)",
    
    "transforms": "route,snakeCase",
    
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "mssql\\\\.mig_test_db\\\\.dbo\\\\.(.*)",
    "transforms.route.replacement": "\$1",
    
    "transforms.snakeCase.type": "com.debezium.transforms.SnakeCaseTransform\$Value",
    
    "table.name.map": {$TOPIC_MAPPINGS
    },
    
    "insert.mode": "upsert",
    "delete.enabled": "true",
    "primary.key.mode": "record_key",
    
    "schema.evolution": "basic",
    "quote.identifiers": "false"
  }
}
EOF

echo ""
echo "Generated: connectors/postgres-sink-generated.json"
echo "Review and deploy with:"
echo "  curl -X POST -H \"Content-Type: application/json\" \\"
echo "    --data @connectors/postgres-sink-generated.json \\"
echo "    http://localhost:8083/connectors"
