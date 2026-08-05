package com.innbucks.marketplaceservice.order;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates the payments-facing order reference: {@code MKT-} + 12 uppercase
 * hex characters (48 random bits from a {@link SecureRandom}). Unguessable
 * enough that the S2S surface can key on it without becoming an enumeration
 * oracle; short enough to read over a support call. Uniqueness is enforced by
 * the {@code uq_order_ref} index — the order flow retries a fresh draw on the
 * (astronomically unlikely) collision.
 */
public final class OrderRefs {

    private static final String PREFIX = "MKT-";
    private static final int RANDOM_BYTES = 6; // 12 hex chars
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat UPPER_HEX = HexFormat.of().withUpperCase();

    private OrderRefs() {
    }

    public static String newRef() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + UPPER_HEX.formatHex(bytes);
    }
}
