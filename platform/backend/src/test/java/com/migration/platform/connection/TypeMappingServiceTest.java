package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.ColumnMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeMappingServiceTest {

    // propose() does not touch the injected dependencies.
    private final TypeMappingService svc = new TypeMappingService(null, null, null);

    private ColumnMapping map(String type, int size, String name) {
        return svc.propose(new ColumnInfo(name, type, size, true, false));
    }

    @Test
    void flagsLossyOrUnsupportedTypesWithANote() {
        assertThat(map("geography", 0, "area").note()).contains("PostGIS");
        assertThat(map("hierarchyid", 0, "node").note()).contains("lossy");
        assertThat(map("sql_variant", 0, "v").note()).contains("lossy");
        // Common, faithful mappings carry no note.
        assertThat(map("int", 10, "id").note()).isNull();
        assertThat(map("nvarchar", 50, "name").note()).isNull();
    }

    @Test
    void perProjectOverrideWinsOverDefault() {
        var def = svc.propose(new ColumnInfo("ts", "datetimeoffset", 34, true, false));
        assertThat(def.proposedType()).isEqualTo("TIMESTAMPTZ(6)");

        var overridden = svc.propose(new ColumnInfo("ts", "datetimeoffset", 34, true, false),
                java.util.Map.of("datetimeoffset", "TIMESTAMPTZ"));
        assertThat(overridden.proposedType()).isEqualTo("TIMESTAMPTZ");
    }

    @Test
    void mapsCommonSqlServerTypesToPostgres() {
        assertThat(map("int", 10, "id").proposedType()).isEqualTo("INTEGER");
        assertThat(map("bigint", 19, "n").proposedType()).isEqualTo("BIGINT");
        assertThat(map("bit", 1, "flag").proposedType()).isEqualTo("BOOLEAN");
        assertThat(map("datetime2", 27, "ts").proposedType()).isEqualTo("TIMESTAMP(6)");
        assertThat(map("datetimeoffset", 34, "ts").proposedType()).isEqualTo("TIMESTAMPTZ(6)");
        assertThat(map("uniqueidentifier", 36, "guid").proposedType()).isEqualTo("UUID");
    }

    @Test
    void varcharKeepsLengthButFallsBackToTextWhenHuge() {
        assertThat(map("varchar", 50, "name").proposedType()).isEqualTo("VARCHAR(50)");
        assertThat(map("nvarchar", -1, "blob").proposedType()).isEqualTo("TEXT");      // MAX
        assertThat(map("nvarchar", 1073741823, "big").proposedType()).isEqualTo("TEXT");
    }

    @Test
    void detectsUuidAndJsonSemantics() {
        assertThat(map("uniqueidentifier", 36, "CustomerGuid").semantic()).isEqualTo("UUID");
        assertThat(map("nvarchar", 4000, "user_metadata").semantic()).isEqualTo("JSON");
        assertThat(map("int", 10, "order_id").semantic()).isEqualTo("NONE"); // int *_id is NOT a UUID
        assertThat(map("xml", 4000, "payload_xml").semantic()).isEqualTo("NONE"); // xml excluded from JSON
    }
}
