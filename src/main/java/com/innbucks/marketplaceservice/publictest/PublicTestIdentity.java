package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the opaque handle in a {@code /marketplace/public/buyers/{handle}/**}
 * path into the {@link AuthenticatedUser} the real services expect.
 *
 * <h2>The handle is NOT a fleet user uuid, and structurally cannot become one</h2>
 * The derived id is a <b>name-based (version 5) UUID</b> over a fixed namespace.
 * user-service mints a customer's {@code userUuid} with
 * {@code UUID.randomUUID()}, which is <b>version 4</b> — the version nibble
 * differs, so a derived id can never equal a real customer's, not merely
 * "probably not". That single fact is what makes this surface safe enough to
 * switch on:
 *
 * <ul>
 *   <li>A caller cannot read or mutate a real customer's cart, address book or
 *       wishlist by guessing or stealing their uuid, because no input to this
 *       function produces a v4 id at all.</li>
 *   <li>A favourite added here belongs to a buyer that does not exist in
 *       user-service, so the restock alert it may later trigger resolves to
 *       nobody and reaches no real person's phone. Favourites are the one
 *       endpoint on this rail with a downstream notification, and this is what
 *       defuses it — see {@code RestockAlertListener}.</li>
 *   <li>Test data written here is therefore its own island. It does NOT carry
 *       over to the same person's authenticated account once
 *       {@code POST /auth/exchange} is live, which is the correct behaviour for
 *       a test rail rather than a shortcoming.</li>
 * </ul>
 *
 * <p>The handle itself is free-form on purpose — {@code alice}, a device id, a
 * uuid the app already has. The app picks one, keeps using it, and gets a
 * stable basket back. It is never stored: only the derived id reaches the
 * database, so nothing a caller types lands in a column.
 */
public final class PublicTestIdentity {

    /**
     * Namespace for the v5 derivation. A fixed, arbitrary constant — it exists
     * to keep these ids in their own space, so changing it orphans every
     * existing public-rail basket. Do not change it casually.
     */
    private static final byte[] NAMESPACE =
            "marketplace-public-test-buyer".getBytes(StandardCharsets.UTF_8);

    /** Bounded so a caller cannot post a megabyte of "handle" to be hashed. */
    static final int MAX_HANDLE_LENGTH = 64;

    private PublicTestIdentity() {
    }

    /**
     * The caller this rail acts as. Always exactly {@code CUSTOMER}: every
     * endpoint behind it is a buyer endpoint, and granting anything wider would
     * put a seller or operator surface behind an unauthenticated path.
     *
     * <p>{@code phone} is deliberately left null. It is what
     * {@code OrderService.resolveBuyerMsisdn} would treat as the payer — the
     * number an EcoCash PIN prompt is delivered to — so a rail that cannot set
     * it cannot aim a payment request at a stranger's handset even if an order
     * endpoint were one day added here by mistake.
     */
    public static AuthenticatedUser buyerFor(String handle) {
        return new AuthenticatedUser(
                derivedUuid(handle).toString(),
                Set.of("CUSTOMER"),
                null,   // merchantId — never a seller
                null,   // shopId
                null,   // phone — see the javadoc above
                null);  // country
    }

    /** The derived buyer id, exposed so responses can echo what the handle became. */
    public static UUID derivedUuid(String handle) {
        String trimmed = handle == null ? "" : handle.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("invalid_handle", "The buyer handle must not be blank.");
        }
        if (trimmed.length() > MAX_HANDLE_LENGTH) {
            throw ApiException.badRequest("invalid_handle",
                    "The buyer handle must be at most " + MAX_HANDLE_LENGTH + " characters.");
        }
        return nameBasedUuid(trimmed);
    }

    /**
     * RFC 4122 §4.3 name-based UUID, SHA-256 truncated to 128 bits rather than
     * the spec's SHA-1. The version nibble is pinned to 5 — that is the half
     * that matters here, since it is what keeps these ids disjoint from
     * user-service's v4 ids; the digest choice is only about not reaching for
     * SHA-1 in new code.
     */
    private static UUID nameBasedUuid(String name) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        digest.update(NAMESPACE);
        digest.update((byte) 0x1F);   // unit separator: "ns" + "x" cannot collide with "n" + "sx"
        byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));

        hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);   // version 5
        hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);   // IETF variant

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (hash[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (hash[i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}
