package com.innbucks.marketplaceservice.fulfilment.tracking;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Parcel tracking codes: {@code TRK-} plus ten Crockford base32 characters, e.g.
 * {@code TRK-7F3K9Q2M4X}.
 *
 * <p>A tracking code is a LOOKUP KEY for people who are already allowed to see
 * the parcel (the seller's portal, an operator, the buyer's own app) — never a
 * credential. Nothing is served to someone who merely holds a code, which is
 * why it needs no hashing and is stored as-is, unlike the collection code.
 * Random rather than sequential all the same, so one code says nothing about
 * how many parcels exist or which is next.
 *
 * <p>Crockford's alphabet (no I, L, O, U) because it is read off a screen and
 * typed into a search box; {@link #normalize} folds the look-alikes back.
 */
public final class TrackingCodes {

    public static final String PREFIX = "TRK-";
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final String VALID = new String(ALPHABET);
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TrackingCodes() {
    }

    public static String mint() {
        StringBuilder code = new StringBuilder(PREFIX.length() + LENGTH).append(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /**
     * What someone typed, in stored form: case ignored, spaces and dashes
     * ignored, the prefix optional, and I/L → 1 and O → 0 folded. Returns null
     * for input that cannot be a tracking code, so a lookup never runs on it.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if (compact.startsWith("TRK")) {
            compact = compact.substring(3);
        }
        StringBuilder body = new StringBuilder(compact.length());
        for (char c : compact.toCharArray()) {
            switch (c) {
                case 'I', 'L' -> body.append('1');
                case 'O' -> body.append('0');
                default -> body.append(c);
            }
        }
        if (body.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < body.length(); i++) {
            // Anything outside the alphabet cannot be a code we minted (or a
            // V14 backfill, which is hex and so inside it): refuse it here
            // rather than send it to the database.
            if (VALID.indexOf(body.charAt(i)) < 0) {
                return null;
            }
        }
        return PREFIX + body;
    }
}
