package com.migration.platform.quality;

import java.util.regex.Pattern;

/**
 * PII detection + masking (#114). Detection combines column-name hints with value patterns;
 * masking is irreversible (one-way), so masked exports can't be de-anonymized.
 */
public final class Pii {

    private Pii() {}

    public enum Category { NONE, EMAIL, PHONE, SSN, CREDIT_CARD, NAME }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[+]?[\\d ()\\-]{7,}$");
    private static final Pattern SSN = Pattern.compile("^\\d{3}-?\\d{2}-?\\d{4}$");
    private static final Pattern CC = Pattern.compile("^\\d{13,19}$");

    public static Category detect(String columnName, String sampleValue) {
        String col = columnName == null ? "" : columnName.toLowerCase();
        if (col.contains("email") || col.contains("e_mail")) return Category.EMAIL;
        if (col.contains("ssn") || col.contains("social")) return Category.SSN;
        if (col.contains("phone") || col.contains("mobile")) return Category.PHONE;
        if (col.contains("card") || col.contains("ccnum")) return Category.CREDIT_CARD;
        if (col.equals("name") || col.endsWith("_name") || col.contains("firstname") || col.contains("lastname"))
            return Category.NAME;
        if (sampleValue != null) {
            String v = sampleValue.trim();
            if (EMAIL.matcher(v).matches()) return Category.EMAIL;
            if (SSN.matcher(v).matches()) return Category.SSN;
            if (CC.matcher(v).matches()) return Category.CREDIT_CARD;
            if (PHONE.matcher(v).matches() && v.replaceAll("\\D", "").length() >= 7) return Category.PHONE;
        }
        return Category.NONE;
    }

    /** Irreversible mask preserving just enough shape to stay useful (e.g. domain, last 4). */
    public static String mask(String value, Category category) {
        if (value == null || value.isEmpty()) return value;
        return switch (category) {
            case EMAIL -> {
                int at = value.indexOf('@');
                yield at > 0 ? "***@" + value.substring(at + 1) : "***";
            }
            case CREDIT_CARD -> value.length() >= 4 ? "****-****-****-" + value.substring(value.length() - 4) : "****";
            case SSN -> "***-**-" + (value.length() >= 4 ? value.substring(value.length() - 4) : "****");
            case PHONE -> {
                String d = value.replaceAll("\\D", "");
                yield d.length() >= 2 ? "*******" + d.substring(d.length() - 2) : "*******";
            }
            case NAME -> value.charAt(0) + "****";
            case NONE -> value;
        };
    }
}
