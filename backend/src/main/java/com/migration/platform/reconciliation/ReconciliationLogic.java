package com.migration.platform.reconciliation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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

    /** Separator joining composite-PK parts; mirrors {@code chr(1)} in the target key expression (#217). */
    public static final String KEY_SEP = String.valueOf((char) 1);

    /**
     * Composite key from PK column values in key order (#217 online content validation): each part is
     * normalised like {@link #normalizeKey} and joined with {@link #KEY_SEP}. Returns null if ANY part is
     * null — a row with a null PK component can't be reliably matched, so it's skipped.
     */
    public static String compositeKey(List<Object> pkValuesInKeyOrder) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkValuesInKeyOrder.size(); i++) {
            String part = normalizeKey(pkValuesInKeyOrder.get(i));
            if (part == null) return null;
            if (i > 0) sb.append(KEY_SEP);
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * Canonicalises a column value so equivalent MSSQL/PostgreSQL representations hash the same
     * (best effort): numbers via BigDecimal (1.50 == 1.5), timestamps truncated to seconds, bytes
     * base64, null as a sentinel. Strings are only trimmed (no numeric coercion).
     */
    public static String normalizeValue(Object v) {
        if (v == null) return "∅";
        if (v instanceof byte[] b) return Base64.getEncoder().encodeToString(b);
        if (v instanceof Boolean bool) return bool ? "true" : "false";
        if (v instanceof Number n) {
            try { return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException e) { return n.toString(); }
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        }
        if (v instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        }
        if (v instanceof java.time.Instant inst) {
            return inst.truncatedTo(ChronoUnit.SECONDS).toString();
        }
        return v.toString().trim();
    }

    /** SHA-256 (hex) over normalized values in a stable column order — a row content checksum. */
    public static String rowChecksum(List<String> normalizedValuesInColumnOrder) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(String.join("", normalizedValuesInColumnOrder)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Checksum failed", e);
        }
    }

    private ReconciliationLogic() {}
}
