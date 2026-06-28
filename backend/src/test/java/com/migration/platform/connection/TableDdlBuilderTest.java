package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnMapping;
import com.migration.platform.connector.NamingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-dialect CREATE TABLE generation for the bulk-copy full load (#191). */
class TableDdlBuilderTest {

    private final TableDdlBuilder ddl = new TableDdlBuilder();

    private static final List<ColumnMapping> COLS = List.of(
            new ColumnMapping("Id", "int", 0, false, true, "INTEGER", "NONE", null),
            new ColumnMapping("Name", "varchar", 100, true, false, "VARCHAR(100)", "NONE", null));

    @Test
    void postgresQuotesWithDoubleQuotesAndSchemaQualifies() {
        String sql = ddl.createTable(DbType.POSTGRESQL, "public", "Users", COLS, NamingStrategy.PRESERVE);
        assertThat(sql).contains("CREATE TABLE \"public\".\"Users\" (");
        assertThat(sql).contains("\"Id\" INTEGER NOT NULL");
        assertThat(sql).contains("\"Name\" VARCHAR(100)");
        assertThat(sql).contains("PRIMARY KEY (\"Id\")");
    }

    @Test
    void sqlServerUsesBrackets() {
        String sql = ddl.createTable(DbType.SQLSERVER, "dbo", "Users", COLS, NamingStrategy.PRESERVE);
        assertThat(sql).contains("CREATE TABLE [dbo].[Users] (");
        assertThat(sql).contains("[Id] INTEGER NOT NULL");
        assertThat(sql).contains("PRIMARY KEY ([Id])");
    }

    @Test
    void mysqlUsesBackticksAndNoSchemaSegment() {
        String sql = ddl.createTable(DbType.MYSQL, "ignored", "Users", COLS, NamingStrategy.PRESERVE);
        assertThat(sql).contains("CREATE TABLE `Users` (");
        assertThat(sql).doesNotContain("ignored");
        assertThat(sql).contains("`Id` INTEGER NOT NULL");
        assertThat(sql).contains("PRIMARY KEY (`Id`)");
    }

    @Test
    void oracleAndDb2QuoteWithDoubleQuotes() {
        assertThat(ddl.createTable(DbType.ORACLE, "APP", "Users", COLS, NamingStrategy.PRESERVE))
                .contains("CREATE TABLE \"APP\".\"Users\" (");
        assertThat(ddl.createTable(DbType.DB2, "APP", "Users", COLS, NamingStrategy.PRESERVE))
                .contains("CREATE TABLE \"APP\".\"Users\" (");
    }

    @Test
    void namingStrategyRenamesColumns() {
        // The builder renames columns per the strategy; the caller passes the already-renamed table name.
        List<ColumnMapping> cols = List.of(
                new ColumnMapping("FirstName", "varchar", 50, false, true, "VARCHAR(50)", "NONE", null));
        String sql = ddl.createTable(DbType.POSTGRESQL, "public", "user_profile", cols, NamingStrategy.SNAKE_CASE);
        assertThat(sql).contains("\"first_name\" VARCHAR(50) NOT NULL");
        assertThat(sql).contains("PRIMARY KEY (\"first_name\")");
    }

    @Test
    void dropIfExistsFallsBackToPlainDropForOracleAndDb2() {
        assertThat(ddl.dropIfExists(DbType.POSTGRESQL, "public", "Users")).isEqualTo("DROP TABLE IF EXISTS \"public\".\"Users\"");
        assertThat(ddl.dropIfExists(DbType.ORACLE, "APP", "Users")).isEqualTo("DROP TABLE \"APP\".\"Users\"");
        assertThat(ddl.dropIfExists(DbType.DB2, "APP", "Users")).isEqualTo("DROP TABLE \"APP\".\"Users\"");
    }
}
