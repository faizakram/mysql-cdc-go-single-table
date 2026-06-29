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
    void decimalPreservesPrecisionAndScaleAcrossEngines() {
        // #197: a NUMERIC(12,2) must not collapse to a bare type (scale 0) and round values.
        assertThat(TypeMappingMatrix.map(DbType.POSTGRESQL, DbType.MYSQL, "numeric", 12, 2).targetType())
                .isEqualTo("DECIMAL(12, 2)");
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "decimal", 10, 4).targetType())
                .isEqualTo("NUMERIC(10, 4)");
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.ORACLE, "decimal", 18, 6).targetType())
                .isEqualTo("NUMBER(18, 6)");
        // Homogeneous decimal also keeps (p,s).
        assertThat(TypeMappingMatrix.map(DbType.POSTGRESQL, DbType.POSTGRESQL, "numeric", 9, 3).targetType())
                .isEqualTo("NUMERIC(9, 3)");
        // Scale 0 (integers-as-decimal) renders DECIMAL(p, 0); unknown precision stays bare.
        assertThat(TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "number", 8, 0).targetType())
                .isEqualTo("NUMERIC(8, 0)");
        assertThat(TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "number", 0, 0).targetType())
                .isEqualTo("NUMERIC");
    }

    @Test
    void unmappableTypeIsFlaggedNotSilent() {
        TypeMappingMatrix.Mapped m = TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "sdo_geometry", 0);
        assertThat(m.targetType()).isEqualTo("TEXT");
        assertThat(m.note()).contains("No canonical mapping");
    }

    @Test
    void modifiersAndPrecisionAreNormalized() {
        // IDENTITY / zerofill modifiers stripped -> base type maps cleanly.
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "int identity", 0).targetType())
                .isEqualTo("INTEGER");
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "bigint identity", 0).note()).isNull();
        // unsigned is recognized (and widened, see below) rather than dropped silently.
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "int unsigned", 0).targetType())
                .isEqualTo("BIGINT");
        // Precision/length parens stripped -> still categorizes (NUMBER(10,2) -> NUMERIC, datetime2(7) ok).
        assertThat(TypeMappingMatrix.map(DbType.ORACLE, DbType.POSTGRESQL, "NUMBER(10,2)", 0).targetType())
                .isEqualTo("NUMERIC");
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "datetime2(7)", 0).note()).isNull();
    }

    @Test
    void unsignedIntegersAreWidenedToAvoidOverflow() {
        // #182: an unsigned max would overflow the same-width signed target — widen one step.
        var smallU = TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "tinyint unsigned", 0);
        assertThat(smallU.targetType()).isEqualTo("INTEGER");
        assertThat(smallU.note()).contains("widened");

        var intU = TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "int unsigned", 0);
        assertThat(intU.targetType()).isEqualTo("BIGINT");
        assertThat(intU.note()).contains("widened");

        // bigint unsigned (up to ~1.8e19) exceeds BIGINT -> NUMERIC(20,0).
        var bigU = TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "bigint unsigned", 0);
        assertThat(bigU.targetType()).isEqualTo("NUMERIC(20, 0)");
        assertThat(bigU.note()).contains("widened");

        // Signed integers are unchanged and carry no note.
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "int", 0).targetType()).isEqualTo("INTEGER");
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "int", 0).note()).isNull();
    }

    @Test
    void spatialAndEnumMappingsAreFlaggedLossy() {
        // #182: data crosses over but type semantics don't — warn so it's reviewed.
        var geo = TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "geography", 0);
        assertThat(geo.targetType()).isEqualTo("BYTEA");
        assertThat(geo.note()).contains("Spatial");
        assertThat(TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "geometry", 0).note()).contains("Spatial");
        var en = TypeMappingMatrix.map(DbType.MYSQL, DbType.POSTGRESQL, "enum", 0);
        assertThat(en.targetType()).isEqualTo("TEXT");
        assertThat(en.note()).contains("value set");
    }

    @Test
    void nonLossySpecialsBindWithoutWarning() {
        // Specials that map faithfully enough carry no "review" note.
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "timestamp", 0).targetType())
                .isEqualTo("BYTEA"); // rowversion
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "hierarchyid", 0).note()).isNull();
        assertThat(TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "sql_variant", 0).note()).isNull();
        assertThat(TypeMappingMatrix.map(DbType.POSTGRESQL, DbType.MYSQL, "inet", 0).note()).isNull();
    }

    @Test
    void trulyUnknownTypeStillAutoBindsButIsFlagged() {
        // Safety net: an unrecognized type binds to a safe default and is flagged so it can be reviewed.
        TypeMappingMatrix.Mapped m = TypeMappingMatrix.map(DbType.SQLSERVER, DbType.POSTGRESQL, "some_custom_udt", 0);
        assertThat(m.targetType()).isEqualTo("TEXT");
        assertThat(m.note()).contains("No canonical mapping");
    }
}
