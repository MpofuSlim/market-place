package com.innbucks.marketplaceservice.catalog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Strong entity tags for served image bytes: the SHA-256 of exactly the bytes
 * in the response body, so two URLs answering with the same bytes (a width that
 * passes the original through, say) share a tag, and a replaced image never
 * reuses one.
 */
public final class ImageEtags {

    private ImageEtags() {}

    /** Lower-case hex SHA-256 of {@code bytes} — the stored form (64 chars). */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    /** The header form of a stored tag: {@code "<hex>"}. */
    public static String quoted(String hex) {
        return '"' + hex + '"';
    }

    /**
     * RFC 9110 §13.1.2 If-None-Match: weak comparison over a comma-separated
     * list, {@code *} matching any current representation. Null/blank never
     * matches.
     */
    public static boolean matches(String ifNoneMatch, String hex) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || hex == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
                tag = tag.substring(1, tag.length() - 1);
            }
            if (tag.equals(hex)) {
                return true;
            }
        }
        return false;
    }
}
