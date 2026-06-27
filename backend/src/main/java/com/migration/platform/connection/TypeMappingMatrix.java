package com.migration.platform.connection;

import java.util.Map;

/**
 * Engine-pair type mapping (#81). Normalizes a source column type to a canonical category, then
 * renders it for the target engine. Homogeneous pairs (same engine) pass through unchanged.
 *
 * <p>This complements the rich, battle-tested SQL Server → PostgreSQL rules in
 * {@link TypeMappingService} (which remain the path for that specific pair); the matrix covers the
 * newly-supported pairs introduced with multi-engine support (#76).
 */
public final class TypeMappingMatrix {

    private TypeMappingMatrix() {}

    /** Canonical, engine-neutral type categories. */
    public enum Cat { INT16, INT32, INT64, DECIMAL, FLOAT, DOUBLE, BOOL, CHAR, VARCHAR, TEXT,
        DATE, TIME, TIMESTAMP, TIMESTAMPTZ, BINARY, UUID, JSON, UNKNOWN }

    public record Mapped(String targetType, String note) {}

    /** Map a source column type to the target engine's type. */
    public static Mapped map(DbType source, DbType target, String sourceType, int size) {
        String src = sourceType == null ? "" : sourceType.trim().toLowerCase();
        if (source == target) {
            // Homogeneous fast-path: keep the source type verbatim (preserving size where given).
            return new Mapped(sized(sourceType == null ? "TEXT" : sourceType.toUpperCase(), size, src), null);
        }
        Cat cat = canonical(source, src);
        String rendered = render(target, cat, size);
        String note = cat == Cat.UNKNOWN
                ? "No canonical mapping for '" + sourceType + "' (" + source + "→" + target + "); defaulted — review."
                : null;
        return new Mapped(rendered, note);
    }

    private static String sized(String base, int size, String srcLower) {
        boolean sizeable = srcLower.contains("char") || srcLower.contains("varchar");
        return (sizeable && size > 0 && size < 65_535) ? base + "(" + size + ")" : base;
    }

    private static Cat canonical(DbType source, String t) {
        return switch (source) {
            case SQLSERVER -> switch (t) {
                case "tinyint", "smallint" -> Cat.INT16;
                case "int" -> Cat.INT32;
                case "bigint" -> Cat.INT64;
                case "bit" -> Cat.BOOL;
                case "decimal", "numeric", "money", "smallmoney" -> Cat.DECIMAL;
                case "float" -> Cat.DOUBLE;
                case "real" -> Cat.FLOAT;
                case "char", "nchar" -> Cat.CHAR;
                case "varchar", "nvarchar" -> Cat.VARCHAR;
                case "text", "ntext", "xml" -> Cat.TEXT;
                case "date" -> Cat.DATE;
                case "time" -> Cat.TIME;
                case "datetime", "datetime2", "smalldatetime" -> Cat.TIMESTAMP;
                case "datetimeoffset" -> Cat.TIMESTAMPTZ;
                case "binary", "varbinary", "image" -> Cat.BINARY;
                case "uniqueidentifier" -> Cat.UUID;
                default -> Cat.UNKNOWN;
            };
            case MYSQL -> switch (t) {
                case "tinyint", "smallint", "year" -> Cat.INT16;
                case "mediumint", "int", "integer" -> Cat.INT32;
                case "bigint" -> Cat.INT64;
                case "bit", "bool", "boolean" -> Cat.BOOL;
                case "decimal", "numeric", "dec" -> Cat.DECIMAL;
                case "float" -> Cat.FLOAT;
                case "double", "double precision" -> Cat.DOUBLE;
                case "char" -> Cat.CHAR;
                case "varchar" -> Cat.VARCHAR;
                case "text", "tinytext", "mediumtext", "longtext", "enum", "set" -> Cat.TEXT;
                case "date" -> Cat.DATE;
                case "time" -> Cat.TIME;
                case "datetime", "timestamp" -> Cat.TIMESTAMP;
                case "blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary" -> Cat.BINARY;
                case "json" -> Cat.JSON;
                default -> Cat.UNKNOWN;
            };
            case POSTGRESQL -> switch (t) {
                case "smallint", "int2", "smallserial" -> Cat.INT16;
                case "integer", "int", "int4", "serial" -> Cat.INT32;
                case "bigint", "int8", "bigserial" -> Cat.INT64;
                case "boolean", "bool" -> Cat.BOOL;
                case "numeric", "decimal", "money" -> Cat.DECIMAL;
                case "real", "float4" -> Cat.FLOAT;
                case "double precision", "float8" -> Cat.DOUBLE;
                case "char", "bpchar", "character" -> Cat.CHAR;
                case "varchar", "character varying" -> Cat.VARCHAR;
                case "text" -> Cat.TEXT;
                case "date" -> Cat.DATE;
                case "time" -> Cat.TIME;
                case "timestamp", "timestamp without time zone" -> Cat.TIMESTAMP;
                case "timestamptz", "timestamp with time zone" -> Cat.TIMESTAMPTZ;
                case "bytea" -> Cat.BINARY;
                case "uuid" -> Cat.UUID;
                case "json", "jsonb" -> Cat.JSON;
                default -> Cat.UNKNOWN;
            };
            case ORACLE -> switch (t) {
                case "number", "integer" -> Cat.DECIMAL;
                case "binary_float" -> Cat.FLOAT;
                case "float", "binary_double" -> Cat.DOUBLE;
                case "char", "nchar" -> Cat.CHAR;
                case "varchar2", "nvarchar2", "varchar" -> Cat.VARCHAR;
                case "clob", "nclob", "long" -> Cat.TEXT;
                case "date", "timestamp" -> Cat.TIMESTAMP;
                case "timestamp with time zone", "timestamp with local time zone" -> Cat.TIMESTAMPTZ;
                case "raw", "blob", "bfile" -> Cat.BINARY;
                default -> Cat.UNKNOWN;
            };
            case DB2 -> switch (t) {
                case "smallint" -> Cat.INT16;
                case "integer", "int" -> Cat.INT32;
                case "bigint" -> Cat.INT64;
                case "decimal", "numeric", "decfloat" -> Cat.DECIMAL;
                case "real" -> Cat.FLOAT;
                case "double", "float" -> Cat.DOUBLE;
                case "char", "graphic" -> Cat.CHAR;
                case "varchar", "vargraphic" -> Cat.VARCHAR;
                case "clob", "dbclob" -> Cat.TEXT;
                case "date" -> Cat.DATE;
                case "time" -> Cat.TIME;
                case "timestamp" -> Cat.TIMESTAMP;
                case "blob", "binary", "varbinary" -> Cat.BINARY;
                default -> Cat.UNKNOWN;
            };
            // MongoDB BSON types (#100): scalars map directly; documents/arrays become JSON on the target.
            case MONGODB -> switch (t) {
                case "objectid", "string", "symbol" -> Cat.VARCHAR;
                case "int", "int32" -> Cat.INT32;
                case "long", "int64" -> Cat.INT64;
                case "double", "decimal", "decimal128" -> Cat.DOUBLE;
                case "bool", "boolean" -> Cat.BOOL;
                case "date", "timestamp" -> Cat.TIMESTAMP;
                case "binary", "bindata" -> Cat.BINARY;
                case "document", "object", "array", "json" -> Cat.JSON;
                default -> Cat.UNKNOWN;
            };
        };
    }

    private static String render(DbType target, Cat cat, int size) {
        return switch (target) {
            case POSTGRESQL -> Map.ofEntries(
                    e(Cat.INT16, "SMALLINT"), e(Cat.INT32, "INTEGER"), e(Cat.INT64, "BIGINT"),
                    e(Cat.DECIMAL, "NUMERIC"), e(Cat.FLOAT, "REAL"), e(Cat.DOUBLE, "DOUBLE PRECISION"),
                    e(Cat.BOOL, "BOOLEAN"), e(Cat.CHAR, sz("CHAR", size, "TEXT")), e(Cat.VARCHAR, sz("VARCHAR", size, "TEXT")),
                    e(Cat.TEXT, "TEXT"), e(Cat.DATE, "DATE"), e(Cat.TIME, "TIME(6)"), e(Cat.TIMESTAMP, "TIMESTAMP(6)"),
                    e(Cat.TIMESTAMPTZ, "TIMESTAMPTZ(6)"), e(Cat.BINARY, "BYTEA"), e(Cat.UUID, "UUID"),
                    e(Cat.JSON, "JSONB"), e(Cat.UNKNOWN, "TEXT")).get(cat);
            case MYSQL -> Map.ofEntries(
                    e(Cat.INT16, "SMALLINT"), e(Cat.INT32, "INT"), e(Cat.INT64, "BIGINT"),
                    e(Cat.DECIMAL, "DECIMAL"), e(Cat.FLOAT, "FLOAT"), e(Cat.DOUBLE, "DOUBLE"),
                    e(Cat.BOOL, "TINYINT(1)"), e(Cat.CHAR, sz("CHAR", size, "TEXT")), e(Cat.VARCHAR, sz("VARCHAR", size, "TEXT")),
                    e(Cat.TEXT, "LONGTEXT"), e(Cat.DATE, "DATE"), e(Cat.TIME, "TIME"), e(Cat.TIMESTAMP, "DATETIME(6)"),
                    e(Cat.TIMESTAMPTZ, "DATETIME(6)"), e(Cat.BINARY, "LONGBLOB"), e(Cat.UUID, "CHAR(36)"),
                    e(Cat.JSON, "JSON"), e(Cat.UNKNOWN, "TEXT")).get(cat);
            case SQLSERVER -> Map.ofEntries(
                    e(Cat.INT16, "SMALLINT"), e(Cat.INT32, "INT"), e(Cat.INT64, "BIGINT"),
                    e(Cat.DECIMAL, "DECIMAL"), e(Cat.FLOAT, "REAL"), e(Cat.DOUBLE, "FLOAT"),
                    e(Cat.BOOL, "BIT"), e(Cat.CHAR, sz("NCHAR", size, "NVARCHAR(MAX)")), e(Cat.VARCHAR, sz("NVARCHAR", size, "NVARCHAR(MAX)")),
                    e(Cat.TEXT, "NVARCHAR(MAX)"), e(Cat.DATE, "DATE"), e(Cat.TIME, "TIME"), e(Cat.TIMESTAMP, "DATETIME2"),
                    e(Cat.TIMESTAMPTZ, "DATETIMEOFFSET"), e(Cat.BINARY, "VARBINARY(MAX)"), e(Cat.UUID, "UNIQUEIDENTIFIER"),
                    e(Cat.JSON, "NVARCHAR(MAX)"), e(Cat.UNKNOWN, "NVARCHAR(MAX)")).get(cat);
            case ORACLE -> Map.ofEntries(
                    e(Cat.INT16, "NUMBER(5)"), e(Cat.INT32, "NUMBER(10)"), e(Cat.INT64, "NUMBER(19)"),
                    e(Cat.DECIMAL, "NUMBER"), e(Cat.FLOAT, "BINARY_FLOAT"), e(Cat.DOUBLE, "BINARY_DOUBLE"),
                    e(Cat.BOOL, "NUMBER(1)"), e(Cat.CHAR, sz("CHAR", size, "CLOB")), e(Cat.VARCHAR, sz("VARCHAR2", size, "CLOB")),
                    e(Cat.TEXT, "CLOB"), e(Cat.DATE, "DATE"), e(Cat.TIME, "TIMESTAMP"), e(Cat.TIMESTAMP, "TIMESTAMP"),
                    e(Cat.TIMESTAMPTZ, "TIMESTAMP WITH TIME ZONE"), e(Cat.BINARY, "BLOB"), e(Cat.UUID, "VARCHAR2(36)"),
                    e(Cat.JSON, "CLOB"), e(Cat.UNKNOWN, "CLOB")).get(cat);
            case DB2 -> Map.ofEntries(
                    e(Cat.INT16, "SMALLINT"), e(Cat.INT32, "INTEGER"), e(Cat.INT64, "BIGINT"),
                    e(Cat.DECIMAL, "DECIMAL"), e(Cat.FLOAT, "REAL"), e(Cat.DOUBLE, "DOUBLE"),
                    e(Cat.BOOL, "SMALLINT"), e(Cat.CHAR, sz("CHAR", size, "CLOB")), e(Cat.VARCHAR, sz("VARCHAR", size, "CLOB")),
                    e(Cat.TEXT, "CLOB"), e(Cat.DATE, "DATE"), e(Cat.TIME, "TIME"), e(Cat.TIMESTAMP, "TIMESTAMP"),
                    e(Cat.TIMESTAMPTZ, "TIMESTAMP"), e(Cat.BINARY, "BLOB"), e(Cat.UUID, "VARCHAR(36)"),
                    e(Cat.JSON, "CLOB"), e(Cat.UNKNOWN, "CLOB")).get(cat);
            // MongoDB is source-only (canSink=false) — never a render target; covered for exhaustiveness.
            case MONGODB -> throw new IllegalArgumentException("MongoDB cannot be a migration target");
        };
    }

    private static Map.Entry<Cat, String> e(Cat c, String v) { return Map.entry(c, v); }

    private static String sz(String base, int size, String overflow) {
        return (size > 0 && size < 65_535) ? base + "(" + size + ")" : overflow;
    }
}
