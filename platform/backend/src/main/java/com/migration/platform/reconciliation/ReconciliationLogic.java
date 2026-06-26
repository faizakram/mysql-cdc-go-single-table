package com.migration.platform.reconciliation;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure decision logic for reconciliation (issues #47/#48), separated from JDBC so it is unit-tested.
 * Correctness of the whole feature hinges on these: the target name must match the SnakeCaseTransform
 * SMT, and the count/sample outcomes drive MATCH/MISMATCH.
 */
public final class ReconciliationLogic {

    public record CountOutcome(long difference, String status) {}
    public record SampleOutcome(long sampled, long missing, String status) {}

    /** Mirrors the SnakeCaseTransform SMT so reconciliation queries the same target names. */
    public static String snakeCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        result = result.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return result.toLowerCase();
    }

    public static CountOutcome countOutcome(long source, long target) {
        return new CountOutcome(source - target, source == target ? "MATCH" : "MISMATCH");
    }

    /** Sampled source keys vs the keys found present in the target → sampled/missing/status. */
    public static SampleOutcome evaluateSample(Collection<String> sample, Collection<String> present) {
        Set<String> presentSet = new HashSet<>(present);
        long missing = sample.stream().filter(k -> !presentSet.contains(k)).count();
        return new SampleOutcome(sample.size(), missing, missing == 0 ? "MATCH" : "MISMATCH");
    }

    /** Normalises a primary-key value so SQL Server and PostgreSQL forms compare (e.g. GUID case). */
    public static String normalizeKey(Object value) {
        return value == null ? null : value.toString().trim().toLowerCase();
    }

    private ReconciliationLogic() {}
}
