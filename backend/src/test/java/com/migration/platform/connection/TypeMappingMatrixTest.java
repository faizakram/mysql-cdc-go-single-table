package com.migration.platform.connection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeMappingMatrixTest {

    @Test
    void homogeneousPassesTypeThroughUnchanged() {
        assertThat(TypeMappingMatrix.map(DbType.POSTGRESQL, DbType.POSTGRESQL, "integer", 0).targetType())
                .isEqualTo("INTEGER");
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.MYSQL, "varchar", 120).targetType())
                .isEqualTo("VARCHAR(120)");
    }

    @Test
    void heterogeneousMapsCommonTypes() {
        // MySQL tinyint -> PostgreSQL SMALLINT
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "tinyint", 0).targetType())
                .isEqualTo("SMALLINT");
        // Oracle NUMBER -> PostgreSQL NUMERIC
        assertThat(TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "NUMBER", 0).targetType())
                .isEqualTo("NUMERIC");
        // PostgreSQL uuid -> MySQL CHAR(36)
        assertThat(TypeMappingMatrix.map(DbType.POSTGRESQL, DbType.MYSQL, "uuid", 0).targetType())
                .isEqualTo("CHAR(36)");
        // MySQL json -> PostgreSQL JSONB
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "json", 0).targetType())
                .isEqualTo("JSONB");
        // varchar size carried across (MySQL -> SQL Server uses NVARCHAR)
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.SQLSERVER, "varchar", 50).targetType())
                .isEqualTo("NVARCHAR(50)");
    }

    @Test
    void unmappableTypeIsFlaggedNotSilent() {
        TypeMappingMatrix.Mapped m = TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "sdo_geometry", 0);
        assertThat(m.targetType()).isEqualTo("TEXT");
        assertThat(m.note()).contains("No canonical mapping");
    }

    @Test
    void modifiersAndPrecisionAreNormalized() {
        // IDENTITY / unsigned / zerofill modifiers stripped -> base type maps cleanly.
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "int identity", 0).targetType())
                .isEqualTo("INTEGER");
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "bigint identity", 0).note()).isNull();
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "int unsigned", 0).note()).isNull();
        // Precision/length parens stripped -> still categorizes (NUMBER(10,2) -> NUMERIC, datetime2(7) ok).
        assertThat(TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "NUMBER(10,2)", 0).targetType())
                .isEqualTo("NUMERIC");
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "datetime2(7)", 0).note()).isNull();
    }

    @Test
    void exoticTypesAutoBindWithoutWarning() {
        // SQL Server CLR/special types auto-bind to sensible targets — no "review" warning.
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "timestamp", 0).targetType())
                .isEqualTo("BYTEA"); // rowversion
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "geography", 0).note()).isNull();
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "hierarchyid", 0).note()).isNull();
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "sql_variant", 0).note()).isNull();
        // Postgres / MySQL specials too.
        assertThat(TypeMappingMatrix.map(DbType.POSTGRESQL, DbType.MYSQL, "inet", 0).note()).isNull();
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "geometry", 0).note()).isNull();
    }

    @Test
    void trulyUnknownTypeStillAutoBindsButIsFlagged() {
        // Safety net: an unrecognized type binds to a safe default and is flagged so it can be reviewed.
        TypeMappingMatrix.Mapped m = TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "some_custom_udt", 0);
        assertThat(m.targetType()).isEqualTo("TEXT");
        assertThat(m.note()).contains("No canonical mapping");
    }
}
