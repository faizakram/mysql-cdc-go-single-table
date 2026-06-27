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
}
