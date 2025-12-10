package com.debezium.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Custom SMT to convert data types for PostgreSQL native types.
 * Converts TEXT fields to UUID or JSONB based on column names or patterns.
 */
public class TypeConversionTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final String UUID_COLUMNS_CONFIG = "uuid.columns";
    private static final String JSON_COLUMNS_CONFIG = "json.columns";
    private static final String UUID_PATTERN_CONFIG = "uuid.pattern";
    private static final String JSON_PATTERN_CONFIG = "json.pattern";

    private static final Pattern DEFAULT_UUID_PATTERN = Pattern.compile(".*(_id|_guid|ID|GUID)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_JSON_PATTERN = Pattern.compile(".*(preferences|settings|metadata|response|_json)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_PATTERN = Pattern.compile(".*(xml|_xml|XML).*", Pattern.CASE_INSENSITIVE);

    private Map<String, String> uuidColumns = new HashMap<>();
    private Map<String, String> jsonColumns = new HashMap<>();
    private Pattern uuidPattern;
    private Pattern jsonPattern;

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, configs);
        
        // Parse UUID columns configuration
        String uuidCols = config.getString(UUID_COLUMNS_CONFIG);
        if (uuidCols != null && !uuidCols.trim().isEmpty()) {
            for (String col : uuidCols.split(",")) {
                String trimmed = col.trim();
                uuidColumns.put(trimmed.toLowerCase(), trimmed);
            }
        }
        
        // Parse JSON columns configuration
        String jsonCols = config.getString(JSON_COLUMNS_CONFIG);
        if (jsonCols != null && !jsonCols.trim().isEmpty()) {
            for (String col : jsonCols.split(",")) {
                String trimmed = col.trim();
                jsonColumns.put(trimmed.toLowerCase(), trimmed);
            }
        }
        
        // Parse UUID pattern
        String uuidPatternStr = config.getString(UUID_PATTERN_CONFIG);
        this.uuidPattern = (uuidPatternStr != null && !uuidPatternStr.trim().isEmpty()) 
            ? Pattern.compile(uuidPatternStr, Pattern.CASE_INSENSITIVE) 
            : DEFAULT_UUID_PATTERN;
        
        // Parse JSON pattern
        String jsonPatternStr = config.getString(JSON_PATTERN_CONFIG);
        this.jsonPattern = (jsonPatternStr != null && !jsonPatternStr.trim().isEmpty()) 
            ? Pattern.compile(jsonPatternStr, Pattern.CASE_INSENSITIVE) 
            : DEFAULT_JSON_PATTERN;
    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }

        if (!(record.value() instanceof Struct)) {
            return record;
        }

        final Struct value = (Struct) record.value();
        final Schema schema = value.schema();

        // Build new schema with converted types
        Schema newSchema = convertSchema(schema);
        
        if (newSchema == schema) {
            // No changes needed
            return record;
        }

        // Build new struct with converted values
        Struct newValue = convertValue(value, newSchema);

        return record.newRecord(
            record.topic(),
            record.kafkaPartition(),
            record.keySchema(),
            record.key(),
            newSchema,
            newValue,
            record.timestamp()
        );
    }

    private Schema convertSchema(Schema schema) {
        SchemaBuilder builder = SchemaBuilder.struct();
        builder.name(schema.name());
        builder.version(schema.version());
        
        if (schema.doc() != null) {
            builder.doc(schema.doc());
        }
        
        if (schema.defaultValue() != null) {
            builder.defaultValue(schema.defaultValue());
        }

        boolean hasChanges = false;
        
        for (Field field : schema.fields()) {
            String fieldName = field.name();
            Schema fieldSchema = field.schema();
            
            // Check if this field should be converted
            Schema newFieldSchema = convertFieldSchema(fieldName, fieldSchema);
            
            if (newFieldSchema != fieldSchema) {
                hasChanges = true;
            }
            
            builder.field(fieldName, newFieldSchema);
        }

        return hasChanges ? builder.build() : schema;
    }

    private Schema convertFieldSchema(String fieldName, Schema fieldSchema) {
        // Handle optional schemas
        if (fieldSchema.isOptional() && fieldSchema.type() == Schema.Type.STRING) {
            Schema convertedSchema = getConvertedSchema(fieldName);
            if (convertedSchema != null) {
                return SchemaBuilder.string()
                    .optional()
                    .name(convertedSchema.name())
                    .parameter("__debezium.source.column.type", convertedSchema.name())
                    .parameter("__postgres.type", getPostgresType(convertedSchema.name()))
                    .build();
            }
        } else if (fieldSchema.type() == Schema.Type.STRING) {
            Schema convertedSchema = getConvertedSchema(fieldName);
            if (convertedSchema != null) {
                return SchemaBuilder.string()
                    .name(convertedSchema.name())
                    .parameter("__debezium.source.column.type", convertedSchema.name())
                    .parameter("__postgres.type", getPostgresType(convertedSchema.name()))
                    .build();
            }
        }
        
        return fieldSchema;
    }

    private Schema getConvertedSchema(String fieldName) {
        String lowerFieldName = fieldName.toLowerCase();
        
        // Skip XML columns - they cannot be converted to JSON
        if (lowerFieldName.contains("xml") || lowerFieldName.contains("_xml")) {
            return null;
        }
        
        // Check explicit UUID columns
        if (uuidColumns.containsKey(lowerFieldName)) {
            return SchemaBuilder.string().name("io.debezium.data.Uuid").build();
        }
        
        // Check explicit JSON columns
        if (jsonColumns.containsKey(lowerFieldName)) {
            return SchemaBuilder.string().name("io.debezium.data.Json").build();
        }
        
        // Check UUID pattern
        if (uuidPattern.matcher(fieldName).matches()) {
            return SchemaBuilder.string().name("io.debezium.data.Uuid").build();
        }
        
        // Check JSON pattern
        if (jsonPattern.matcher(fieldName).matches()) {
            return SchemaBuilder.string().name("io.debezium.data.Json").build();
        }
        
        return null;
    }

    private String getPostgresType(String schemaName) {
        if ("io.debezium.data.Uuid".equals(schemaName)) {
            return "UUID";
        } else if ("io.debezium.data.Json".equals(schemaName)) {
            return "JSONB";
        }
        return "TEXT";
    }

    private Struct convertValue(Struct value, Schema newSchema) {
        Struct newValue = new Struct(newSchema);
        
        for (Field field : value.schema().fields()) {
            String fieldName = field.name();
            Object fieldValue = value.get(field);
            
            // Copy value as-is (type conversion happens at database level based on schema hints)
            newValue.put(fieldName, fieldValue);
        }
        
        return newValue;
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
        // No resources to clean up
    }

    private static final ConfigDef CONFIG_DEF = new ConfigDef()
        .define(UUID_COLUMNS_CONFIG, ConfigDef.Type.STRING, "",
            ConfigDef.Importance.HIGH,
            "Comma-separated list of column names to convert to UUID (e.g., user_id,session_id)")
        .define(JSON_COLUMNS_CONFIG, ConfigDef.Type.STRING, "",
            ConfigDef.Importance.HIGH,
            "Comma-separated list of column names to convert to JSONB (e.g., user_preferences,api_response)")
        .define(UUID_PATTERN_CONFIG, ConfigDef.Type.STRING, "",
            ConfigDef.Importance.MEDIUM,
            "Regex pattern to identify UUID columns (default: .*(_id|_guid|ID|GUID)$)")
        .define(JSON_PATTERN_CONFIG, ConfigDef.Type.STRING, "",
            ConfigDef.Importance.MEDIUM,
            "Regex pattern to identify JSON columns (default: .*(preferences|settings|metadata|config|response|_json)$)");
}
