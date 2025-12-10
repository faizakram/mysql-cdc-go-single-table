package com.debezium.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SchemaUtil;

import java.util.Map;

/**
 * Kafka Connect SMT to convert PascalCase/camelCase field names to snake_case.
 * 
 * Usage in connector config:
 * "transforms": "snakeCase",
 * "transforms.snakeCase.type": "com.debezium.transforms.SnakeCaseTransform$Value"
 */
public abstract class SnakeCaseTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    @Override
    public R apply(R record) {
        // Transform topic name to snake_case for table names
        final String originalTopic = record.topic();
        final String snakeCaseTopic = toSnakeCase(originalTopic);

        // Determine which schema and value to transform based on subclass
        final Schema originalSchema = getSchema(record);
        final Object originalValue = getValue(record);
        
        if (originalValue == null || originalSchema == null) {
            // No transformation needed
            return updateRecord(record, snakeCaseTopic, originalSchema, originalValue);
        }

        // Build new schema with snake_case field names
        final Schema updatedSchema = makeUpdatedSchema(originalSchema);
        
        // Convert the value to new schema
        final Object updatedValue;
        if (originalValue instanceof Struct) {
            updatedValue = convertStruct((Struct) originalValue, updatedSchema);
        } else {
            updatedValue = originalValue;
        }

        // Return new record with snake_case topic and schema
        return updateRecord(record, snakeCaseTopic, updatedSchema, updatedValue);
    }

    /**
     * Get the schema to transform (overridden by Key/Value subclasses)
     */
    protected abstract Schema getSchema(R record);

    /**
     * Get the value to transform (overridden by Key/Value subclasses)
     */
    protected abstract Object getValue(R record);

    /**
     * Create updated record (overridden by Key/Value subclasses)
     */
    protected abstract R updateRecord(R record, String newTopic, Schema updatedSchema, Object updatedValue);

    /**
     * Create a new schema with snake_case field names
     */
    private Schema makeUpdatedSchema(Schema schema) {
        final SchemaBuilder builder;
        
        switch (schema.type()) {
            case STRUCT:
                builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
                for (Field field : schema.fields()) {
                    String snakeCaseName = toSnakeCase(field.name());
                    // Recursively transform nested struct schemas
                    Schema fieldSchema = field.schema();
                    if (fieldSchema.type() == Schema.Type.STRUCT) {
                        fieldSchema = makeUpdatedSchema(fieldSchema);
                    }
                    builder.field(snakeCaseName, fieldSchema);
                }
                break;
            default:
                return schema;
        }
        
        return builder.build();
    }

    /**
     * Convert a Struct to the new schema with snake_case fields
     */
    private Struct convertStruct(Struct original, Schema newSchema) {
        final Struct updated = new Struct(newSchema);
        
        // Iterate through new schema fields and find matching original fields
        for (Field newField : newSchema.fields()) {
            String snakeCaseName = newField.name();
            
            // Find the corresponding original field
            // We need to check all original fields to find which one converts to this snake_case name
            Field matchingOriginalField = null;
            for (Field originalField : original.schema().fields()) {
                if (toSnakeCase(originalField.name()).equals(snakeCaseName)) {
                    matchingOriginalField = originalField;
                    break;
                }
            }
            
            if (matchingOriginalField != null) {
                Object value = original.get(matchingOriginalField);
                
                // Recursively convert nested structs
                if (value != null && value instanceof Struct && matchingOriginalField.schema().type() == Schema.Type.STRUCT) {
                    value = convertStruct((Struct) value, newField.schema());
                }
                
                // Only put the value if it's not null OR if the field is optional
                if (value != null || newField.schema().isOptional()) {
                    updated.put(snakeCaseName, value);
                }
            }
        }
        
        return updated;
    }

    /**
     * Convert PascalCase/camelCase to snake_case
     * Examples:
     *   EmployeeID -> employee_id
     *   FirstName -> first_name
     *   HTTPSConnection -> https_connection
     *   userName -> user_name
     */
    private String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // Handle sequences of capitals followed by lowercase (HTTPSConnection -> HTTPS_Connection)
        String result = input.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        
        // Handle lowercase followed by uppercase (camelCase -> camel_Case)
        result = result.replaceAll("([a-z\\d])([A-Z])", "$1_$2");
        
        // Convert to lowercase
        return result.toLowerCase();
    }

    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void close() {
        // Nothing to close
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // No configuration needed
    }

    /**
     * Transform for record values
     */
    public static class Value<R extends ConnectRecord<R>> extends SnakeCaseTransform<R> {
        @Override
        protected Schema getSchema(R record) {
            return record.valueSchema();
        }

        @Override
        protected Object getValue(R record) {
            return record.value();
        }

        @Override
        protected R updateRecord(R record, String newTopic, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(
                newTopic,
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                updatedSchema,
                updatedValue,
                record.timestamp()
            );
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
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
    }

    /**
     * Transform for record keys
     */
    public static class Key<R extends ConnectRecord<R>> extends SnakeCaseTransform<R> {
        @Override
        protected Schema getSchema(R record) {
            return record.keySchema();
        }

        @Override
        protected Object getValue(R record) {
            return record.key();
        }

        @Override
        protected R updateRecord(R record, String newTopic, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(
                newTopic,
                record.kafkaPartition(),
                updatedSchema,
                updatedValue,
                record.valueSchema(),
                record.value(),
                record.timestamp()
            );
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(
                record.topic(),
                record.kafkaPartition(),
                updatedSchema,
                updatedValue,
                record.valueSchema(),
                record.value(),
                record.timestamp()
            );
        }
    }
}
