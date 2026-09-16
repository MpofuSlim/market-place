package com.innbucks.marketplaceservice.fulfilment.collect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The collection handover code: minted here, hashed here, compared here.
 *
 * <p>A human has to read this aloud at a counter or copy it off a phone
 * screen, so the alphabet is <b>Crockford base32</b> — no {@code I}, {@code L},
 * {@code O} or {@code U}, which is what stops a 1/I or 0/O mix-up being
 * indistinguishable from a wrong code. On the way back IN we accept the
 * confusable characters and fold them ({@code I}/{@code L} → {@code 1},
 * {@code O} → {@code 0}), so someone who types what they think they see still
 * gets in. Grouping dashes and spaces are cosmetic and are stripped.
 *
 * <p><b>Twelve characters is 60 bits of {@link SecureRandom}</b>, and that
 * number is doing real work: it is why the stored form is a plain SHA-256
 * rather than the keyed HMAC the fleet requires for low-entropy secrets. An OTP
 * is six digits — a million-value dictionary anybody with a database read can
 * enumerate — whereas 2^60 has no dictionary to build. The online guessing
 * budget is bounded separately by the per-parcel attempt cap.
 *
 * <p>The plaintext exists in exactly two places and never at rest: the mint
 * response to the buyer, and the SMS to whoever is collecting. Nothing logs it,
 * no read surface returns it a second time, and no merchant surface has ever
 * seen it — a seller who could read the code could redeem it themselves and
 * take the instant payout without anyone collecting anything.
 */
public final class CollectCodes {

    /** Crockford base32: 10 digits + 22 letters, minus I, L, O and U. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /** 12 × 5 bits = 60 bits. */
    static final int LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CollectCodes() {
    }

    /** A fresh code, ungrouped ({@code K7Q29XMF3TRW}). */
    public static String mint() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /**
     * The same code in reading groups ({@code K7Q2-9XMF-3TRW}) — what the app
     * prints and what someone reads down a phone line. Purely cosmetic:
     * {@link #normalize} removes the dashes again, so either form verifies.
     */
    public static String grouped(String code) {
        String plain = normalize(code);
        StringBuilder out = new StringBuilder(plain.length() + 2);
        for (int i = 0; i < plain.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append('-');
            }
            out.append(plain.charAt(i));
        }
        return out.toString();
    }

    /**
     * What a typed code means: upper-cased, stripped of anything that is not a
     * letter or digit, and with the Crockford confusables folded onto the
     * characters they are mistaken for. A value that normalises to something
     * outside the alphabet simply never matches — there is no separate "malformed
     * code" refusal, deliberately, because telling a guesser which of their
     * attempts was well-formed is free information.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case 'I', 'L' -> out.append('1');
                case 'O' -> out.append('0');
                default -> {
                    if (Character.isLetterOrDigit(c)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** SHA-256 hex of the normalised code — the only form that is ever stored. */
    public static String hash(String rawCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(rawCode).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS; its absence is not a runtime case.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Constant-time hash comparison. The timing of a hash compare leaks little
     * on its own, but the cost of doing it right is one method call and the
     * habit is what keeps the next comparison — over something that does leak —
     * honest.
     */
    public static boolean matches(String candidate, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(candidate).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
