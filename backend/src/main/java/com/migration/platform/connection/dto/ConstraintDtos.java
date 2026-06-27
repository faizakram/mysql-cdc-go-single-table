package com.migration.platform.connection.dto;

import java.util.List;

/** Source index/FK metadata + the result of applying replicated constraints (issue #33). */
public final class ConstraintDtos {

    public record IndexInfo(String name, boolean unique, List<String> columns) {}

    public record ForeignKeyInfo(String name, List<String> columns, String refTable, List<String> refColumns) {}

    /** Generated DDL and the outcome of applying it to the target. */
    public record ConstraintApplyResult(int indexes, int foreignKeys, List<String> statements, List<String> errors) {}

    private ConstraintDtos() {}
}
