package com.migration.platform.common;

/** Identifier helpers shared across the platform. */
public final class Names {

    /** PascalCase/camelCase → snake_case, matching the SnakeCaseTransform SMT used by the sink. */
    public static String snakeCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        result = result.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return result.toLowerCase();
    }

    private Names() {}
}
