package com.migration.platform.connector;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the target identifier for a source name under a {@link NamingStrategy} (#84/#96).
 * Mirrors the runtime SMT so validation/discovery can locate the migrated table/column.
 */
public final class TargetNaming {

    private TargetNaming() {}

    public static String apply(String name, NamingStrategy strategy) {
        if (name == null || name.isEmpty() || strategy == NamingStrategy.PRESERVE) return name;
        List<String> words = tokenize(name);
        if (words.isEmpty()) return name;
        return switch (strategy) {
            case SNAKE_CASE -> String.join("_", words);
            case UPPER_CASE -> String.join("_", words).toUpperCase();
            case CAMEL_CASE -> {
                StringBuilder b = new StringBuilder(words.get(0));
                for (int i = 1; i < words.size(); i++) b.append(cap(words.get(i)));
                yield b.toString();
            }
            case PASCAL_CASE -> {
                StringBuilder b = new StringBuilder();
                for (String w : words) b.append(cap(w));
                yield b.toString();
            }
            default -> name;
        };
    }

    private static List<String> tokenize(String input) {
        String spaced = input
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
                .replace('_', ' ');
        List<String> out = new ArrayList<>();
        for (String p : spaced.trim().split("\\s+")) if (!p.isEmpty()) out.add(p.toLowerCase());
        return out;
    }

    private static String cap(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}
