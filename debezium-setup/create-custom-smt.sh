#!/bin/bash

# Script to deploy custom SMT for PascalCase to snake_case conversion
# This creates a custom Kafka Connect transformation

cat > /tmp/SnakeCaseTransform.java << 'EOF'
package com.example.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;

import java.util.Map;

public class SnakeCaseTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    @Override
    public R apply(R record) {
        if (record.valueSchema() == null) {
            return record;
        }

        Schema updatedSchema = makeUpdatedSchema(record.valueSchema());
        Struct value = (Struct) record.value();
        Struct updatedValue = new Struct(updatedSchema);

        for (Field field : value.schema().fields()) {
            String newFieldName = toSnakeCase(field.name());
            updatedValue.put(newFieldName, value.get(field));
        }

        return record.newRecord(
            record.topic(),
            record.kafkaPartition(),
            record.keySchema(),
            record.key(),
            updatedSchema,
            updatedValue,
            record.timestamp()
        );
    }

    private Schema makeUpdatedSchema(Schema schema) {
        SchemaBuilder builder = SchemaBuilder.struct();
        for (Field field : schema.fields()) {
            builder.field(toSnakeCase(field.name()), field.schema());
        }
        return builder.build();
    }

    private String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replaceAll("([a-z])([A-Z])", "$1_$2")
                    .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
                    .toLowerCase();
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
EOF

echo "Custom SMT created at /tmp/SnakeCaseTransform.java"
echo ""
echo "To use this custom transformation:"
echo "1. Compile the Java class"
echo "2. Package it as a JAR"
echo "3. Copy to Debezium Connect plugins directory"
echo "4. Restart Debezium Connect"
echo ""
echo "OR use the simpler approach with PostgreSQL views (see NAMING_CONVENTION.md)"
