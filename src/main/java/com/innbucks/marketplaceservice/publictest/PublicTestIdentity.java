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
 *   <li>A caller cannot read or mutate data belonging to a real
 *       <em>authenticated</em> account by guessing or stealing its uuid,
 *       because no input to this function produces a v4 id at all.</li>
 *   <li>A favourite added here belongs to a buyer uuid that does not exist in
 *       user-service, so the restock alert it may later trigger resolves to
 *       nobody there. Favourites are the one endpoint on this rail with a
 *       downstream notification — see {@code RestockAlertListener}.</li>
 * </ul>
 *
 * <h2>Two kinds of handle, one derivation</h2>
 * Since the operator's 2026-09-22 direction ("customers exist on Veengu, not
 * my DB — same as loyalty"), the handle is normally the customer's PHONE
 * NUMBER, normalised to E.164 by {@link PublicBuyerResolver} before it reaches
 * this class — so every spelling of a number is one buyer, stable across
 * devices, and no account or linking step exists, exactly like loyalty's
 * public surface. Opaque handles ({@code alice}, a device id — anything with a
 * letter in it) remain for demo data and derive exactly as they always did.
 *
 * <p>The two flavours differ in ONE field: a phone-keyed buyer carries the
 * normalised phone, so orders placed under it are payable BY that phone with
 * no body field — {@code OrderService.resolveBuyerMsisdn} prefers the
 * identity's phone and ignores the body, the same rule a real customer token
 * gets. An opaque buyer stays phone-less: it can only name a payer explicitly
 * in the order body, where the value is validated like any other.
 *
 * <p>Either way the handle is never stored: only the derived id (and, for a
 * phone, the normalised number on the rows that need a payer) reaches the
 * database.
 *
 * <p><b>Carry-over to {@code POST /auth/exchange}, designed but not yet
 * built:</b> a real account's {@code userUuid} is random (v4), so even a
 * phone-keyed basket does not automatically follow the customer into their
 * authenticated session. Because the derivation here is deterministic, the
 * adoption is one re-key per table — {@code buyer_uuid = derivedUuid(phone) →
 * userUuid} — runnable the first time an authenticated caller with that phone
 * claim touches this service. That is the follow-up that makes the switch to
 * exchange seamless; do not fake it by handing this rail v4 ids.
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
     * The OPAQUE-handle caller. Always exactly {@code CUSTOMER}: every
     * endpoint behind it is a buyer endpoint, and granting anything wider would
     * put a seller or operator surface behind an unauthenticated path.
     *
     * <p>{@code phone} is deliberately left null on this flavour. It is what
     * {@code OrderService.resolveBuyerMsisdn} treats as the payer, so an
     * opaque demo buyer can only name one explicitly in the order body, where
     * it is validated like any other number.
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

    /**
     * The PHONE-keyed caller — the customer as the Veengu session knows them.
     *
     * @param e164 the ALREADY-normalised number from
     *             {@link PublicBuyerResolver}; passing a raw spelling here
     *             would fork one customer into as many baskets as there are
     *             ways to type their number, which is the bug the resolver
     *             exists to prevent. The id is derived from this canonical
     *             form, so {@code 0771234567} and {@code +263771234567} are
     *             one buyer — and a handle that already WAS canonical E.164
     *             derives the same id it always has.
     *
     * <p>The phone rides on the principal, which is what makes an order
     * placed under this identity payable by it with no body field: the payer
     * IS the basket's owner, the same invariant a real customer token has.
     */
    public static AuthenticatedUser buyerForPhone(String e164) {
        return new AuthenticatedUser(
                derivedUuid(e164).toString(),
                Set.of("CUSTOMER"),
                null,   // merchantId — never a seller
                null,   // shopId
                e164,   // the payer, by identity rather than by body field
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
