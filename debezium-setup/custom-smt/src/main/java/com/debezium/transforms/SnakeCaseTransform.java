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

    /** Naming strategy (#84): snake_case (default, back-compat) | camel_case | pascal_case | upper_case. */
    private String strategy = "snake_case";

    @Override
    public R apply(R record) {
        // Transform topic name (table) per the configured naming strategy.
        final String snakeCaseTopic = transformName(record.topic());

        // Determine which schema and value to transform based on subclass
        final Schema originalSchema = getSchema(record);
        final Object originalValue = getValue(record);

        // No schema to rename (schemaless record) — just carry the renamed topic.
        if (originalSchema == null) {
            return updateRecord(record, snakeCaseTopic, originalSchema, originalValue);
        }

        // Always rename the SCHEMA field names — even when the value is null. Delete tombstones (HARD
        // delete) arrive with a null value but a populated value schema; if the schema isn't renamed, the
        // JDBC sink compares the original-cased schema against the renamed (e.g. snake_case) target table,
        // decides columns are "missing", and tries to ALTER-ADD them — which fails for NOT NULL columns
        // ("field 'X' is not optional but has no default value"). Renaming the schema keeps the tombstone's
        // columns aligned with the table so the sink just deletes by key.
        final Schema updatedSchema = makeUpdatedSchema(originalSchema);

        // A null value (tombstone) stays null under the renamed schema; a Struct is converted.
        final Object updatedValue = (originalValue instanceof Struct)
                ? convertStruct((Struct) originalValue, updatedSchema)
                : originalValue;

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
                    String snakeCaseName = transformName(field.name());
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
                if (transformName(originalField.name()).equals(snakeCaseName)) {
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
    /**
     * Apply the configured naming strategy to an identifier (#84). Tokenizes the input on
     * underscores and case boundaries, then re-joins per strategy. The bare table name inside a
     * dotted topic (prefix.db.schema.table) keeps its dots; only the final segment is transformed
     * here because Debezium topics arrive as the table name at this stage after routing.
     */
    String transformName(String input) {
        if (input == null || input.isEmpty()) return input;
        // Keep dotted topic prefixes intact; only transform the last segment (the table).
        int dot = input.lastIndexOf('.');
        if (dot >= 0) {
            return input.substring(0, dot + 1) + transformName(input.substring(dot + 1));
        }
        java.util.List<String> words = tokenize(input);
        if (words.isEmpty()) return input;
        switch (strategy) {
            case "upper_case":
                return String.join("_", words).toUpperCase();
            case "camel_case": {
                StringBuilder b = new StringBuilder(words.get(0));
                for (int i = 1; i < words.size(); i++) b.append(capitalize(words.get(i)));
                return b.toString();
            }
            case "pascal_case": {
                StringBuilder b = new StringBuilder();
                for (String w : words) b.append(capitalize(w));
                return b.toString();
            }
            case "snake_case":
            default:
                return String.join("_", words);
        }
    }

    /** Split an identifier into lowercase words on underscores and case boundaries. */
    private java.util.List<String> tokenize(String input) {
        String spaced = input
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")   // HTTPSConn -> HTTPS_Conn
                .replaceAll("([a-z\\d])([A-Z])", "$1_$2")        // camelCase -> camel_Case
                .replace('_', ' ');
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : spaced.trim().split("\\s+")) {
            if (!part.isEmpty()) out.add(part.toLowerCase());
        }
        return out;
    }

    private String capitalize(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

    @Override
    public ConfigDef config() {
        return new ConfigDef().define("strategy", ConfigDef.Type.STRING, "snake_case",
                ConfigDef.Importance.MEDIUM,
                "Naming strategy: snake_case | camel_case | pascal_case | upper_case");
    }

    @Override
    public void close() {
        // Nothing to close
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Object s = configs.get("strategy");
        if (s != null && !s.toString().isBlank()) {
            this.strategy = s.toString().trim().toLowerCase();
        }
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
