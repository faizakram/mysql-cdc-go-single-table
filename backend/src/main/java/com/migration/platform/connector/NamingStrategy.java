package com.migration.platform.connector;

/**
 * Target identifier naming policy (#84). The platform must NOT rename by default — enterprises rely
 * on their existing table/column names. PRESERVE keeps names exactly (quoted on the target so case
 * survives); the others apply a deterministic case transform via the custom SMT.
 */
public enum NamingStrategy {
    PRESERVE,       // keep source names exactly (default)
    SNAKE_CASE,     // first_name
    CAMEL_CASE,     // firstName
    PASCAL_CASE,    // FirstName
    UPPER_CASE;     // FIRST_NAME

    /** The SMT {@code strategy} config value for non-preserve strategies. */
    public String smtValue() {
        return name().toLowerCase();
    }

    public boolean isPreserve() {
        return this == PRESERVE;
    }

    public static NamingStrategy parse(String s) {
        if (s == null || s.isBlank()) return PRESERVE;
        return switch (s.trim().toLowerCase().replace('-', '_')) {
            case "preserve", "none", "original" -> PRESERVE;
            case "snake_case", "snake" -> SNAKE_CASE;
            case "camel_case", "camelcase", "camel" -> CAMEL_CASE;
            case "pascal_case", "pascalcase", "pascal" -> PASCAL_CASE;
            case "upper_case", "uppercase", "upper" -> UPPER_CASE;
            default -> PRESERVE;
        };
    }
}
