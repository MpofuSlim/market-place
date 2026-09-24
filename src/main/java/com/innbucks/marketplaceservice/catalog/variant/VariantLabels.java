package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;

import java.util.Locale;

/**
 * The text rules for option names and values (V19), in one place.
 *
 * <ul>
 *   <li>{@link #label}: "M" or "M - Black". The joiner {@code " - "} (and the
 *       {@code " ("} / {@code ")"} a title adds around it) round-trip the SMS
 *       sanitizer, so a label can ride any platform text unchanged.</li>
 *   <li>{@link #key}: lower-cased values joined by {@code ','} — the ONE
 *       definition of "the same option", stored in {@code option_key} and
 *       used by the service's duplicate check, so Java and the database can
 *       never disagree about what a duplicate is. A value may therefore never
 *       contain {@code ','} ({@link #normalize} refuses it).</li>
 * </ul>
 *
 * Only {@code ','} is refused beyond blanks and length: "Navy/White" and
 * "6/128GB" are real option values. Seller text is sanitised again on its way
 * into an SMS, like a title.
 */
public final class VariantLabels {

    public static final String JOINER = " - ";

    private VariantLabels() {
    }

    public static String label(String value1, String value2) {
        return value2 == null ? value1 : value1 + JOINER + value2;
    }

    public static String key(String value1, String value2) {
        return value1.toLowerCase(Locale.ROOT) + ","
                + (value2 == null ? "" : value2.toLowerCase(Locale.ROOT));
    }

    /**
     * Sanitises one option name or value: HTML stripped, whitespace collapsed
     * and trimmed. Refused (400 {@code invalid_variant_option}, naming
     * {@code field}) when blank, containing a comma or longer than
     * {@code maxLength}.
     */
    public static String normalize(String raw, String field, int maxLength) {
        String sanitized = TextSanitizer.sanitize(raw);
        String value = sanitized == null ? "" : sanitized.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("invalid_variant_option", field + " must not be blank");
        }
        if (value.indexOf(',') >= 0) {
            throw ApiException.badRequest("invalid_variant_option",
                    field + " must not contain a comma");
        }
        if (value.length() > maxLength) {
            throw ApiException.badRequest("invalid_variant_option",
                    field + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
