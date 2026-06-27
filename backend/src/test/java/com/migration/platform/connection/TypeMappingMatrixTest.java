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
    void driverTypeModifiersDoNotMakeCommonTypesUnmappable() {
        // SQL Server's JDBC driver reports IDENTITY columns as "int identity" / "bigint identity";
        // these must map cleanly (no false "unmappable" note that warned on every table).
        TypeMappingMatrix.Mapped idInt = TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "int identity", 0);
        assertThat(idInt.targetType()).isEqualTo("INTEGER");
        assertThat(idInt.note()).isNull();

        TypeMappingMatrix.Mapped idBig = TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "bigint identity", 0);
        assertThat(idBig.targetType()).isEqualTo("BIGINT");
        assertThat(idBig.note()).isNull();

        // MySQL appends " unsigned" / " zerofill" — also base types, not unmappable.
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "int unsigned", 0).note()).isNull();

        // A genuinely unsupported type is still flagged (e.g. SQL Server geography).
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "geography", 0).note())
                .contains("No canonical mapping");
    }
}
