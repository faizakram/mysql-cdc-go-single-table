package com.migration.platform.connection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic tests for the target-schema CREATE DDL + safe identifier quoting. */
class TargetSchemaServiceTest {

    @Test
    void rendersEngineAppropriateCreateDdl() {
        assertThat(TargetSchemaService.createDdl(DbType.POSTGRESQL, "pascal"))
                .isEqualTo("CREATE SCHEMA IF NOT EXISTS \"pascal\"");
        // SQL Server / Db2 have no IF NOT EXISTS — the create is guarded by an existence probe.
        assertThat(TargetSchemaService.createDdl(DbType.SQLSERVER, "pascal"))
                .isEqualTo("CREATE SCHEMA [pascal]");
        assertThat(TargetSchemaService.createDdl(DbType.DB2, "pascal"))
                .isEqualTo("CREATE SCHEMA \"pascal\"");
    }

    @Test
    void quotesIdentifiersSafelyAgainstInjection() {
        // Embedded quotes/brackets are doubled so a crafted schema name can't break out.
        assertThat(TargetSchemaService.quote(DbType.POSTGRESQL, "a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(TargetSchemaService.quote(DbType.SQLSERVER, "a]b")).isEqualTo("[a]]b]");
    }

    @Test
    void qualifiesTargetTableNamesPerEngine() {
        // PostgreSQL / SQL Server: schema-qualified + quoted; case preserved (PascalCase survives).
        assertThat(TargetSchemaService.qualifiedNames(DbType.POSTGRESQL, "offer", java.util.List.of("OrderItems", "Payments")))
                .containsExactly("\"offer\".\"OrderItems\"", "\"offer\".\"Payments\"");
        assertThat(TargetSchemaService.qualifiedNames(DbType.SQLSERVER, "offer", java.util.List.of("OrderItems")))
                .containsExactly("[offer].[OrderItems]");
        // MySQL has no separate schema — table name only, backtick-quoted.
        assertThat(TargetSchemaService.qualifiedNames(DbType.MYSQL, "ignored", java.util.List.of("order_items")))
                .containsExactly("`order_items`");
    }
}
