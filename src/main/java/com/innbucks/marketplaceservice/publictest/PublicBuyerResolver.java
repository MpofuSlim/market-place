package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.notify.MsisdnMasking;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Decides what the {@code {handle}} in a public-rail path IS, and builds the
 * buyer accordingly.
 *
 * <h2>Why the handle is now (usually) a phone number</h2>
 * The super app's customers authenticate at the InnBucks middleware (Veengu) —
 * they exist THERE, not in this fleet's database, and the one identifier both
 * sides agree on is the phone number. Loyalty's public surface has always been
 * keyed on it, which is why points "just work" for these customers with no
 * account step. This rail originally keyed on an opaque test handle instead,
 * deliberately disconnected from anyone real — and the first thing the app
 * team built on top was an "link your account" screen, because a basket under
 * a throwaway identity has to be reconciled with the actual customer sooner or
 * later. The operator's direction (2026-09-22): the phone from the Veengu
 * session is the identity, on every public rail, same as loyalty.
 *
 * <h2>The split, and why it fails closed</h2>
 * <ul>
 *   <li><b>Phone-shaped</b> (digits, optionally {@code +} and separator
 *       punctuation, no letters) → normalised to E.164 by the same
 *       {@link Msisdns} every payer and recipient number goes through, then
 *       hashed. {@code 0771234567} and {@code +263771234567} are therefore the
 *       SAME buyer — the whole point, since a customer must get the same
 *       basket on every device and every spelling.</li>
 *   <li><b>Phone-shaped but not a dialable number</b> → refused
 *       ({@code 400 invalid_msisdn}), never silently hashed as an opaque
 *       handle. Falling through would fork a typo into its own empty basket,
 *       which presents as "my cart disappeared" and is undebuggable from the
 *       outside.</li>
 *   <li><b>Anything with a letter in it</b> → the original opaque-handle
 *       derivation, unchanged. Demo data under {@code alice} keeps working,
 *       and a build script can still mint disposable buyers at will.</li>
 * </ul>
 *
 * <h2>What changed about the safety argument — stated, not hidden</h2>
 * A phone-keyed buyer carries the normalised phone as {@code phone()}, so
 * {@code OrderService.resolveBuyerMsisdn} treats it as the payer and IGNORES
 * any body value — the number an EcoCash prompt reaches is the number whose
 * basket it is, exactly as with a real customer token. The flip side: whoever
 * holds the cell's api-key can act as any phone's basket. That is loyalty's
 * long-standing posture ("the key authenticates the BROKER; the broker asserts
 * the phone it authenticated at Veengu"), accepted here knowingly for staging,
 * and it is what {@code POST /auth/exchange} retires — an assertion proves the
 * phone cryptographically instead of a trusted caller asserting it. The v4/v5
 * uuid disjointness is unchanged: a derived id still cannot equal any real
 * {@code userUuid}, so authenticated-surface data stays unreachable from here.
 */
@Component
@RequiredArgsConstructor
public class PublicBuyerResolver {

    /**
     * "The caller is trying to send a phone number": nothing but digits and
     * the punctuation people put inside one — which includes a LEADING
     * {@code (}, as in {@code (077) 123-4567}. A single letter anywhere makes
     * it an opaque handle instead, so the two spaces cannot blur, and only
     * phone-shaped inputs are held to phone validation. At least one digit is
     * required so pure punctuation stays a (harmless, opaque) handle rather
     * than a doomed normalisation attempt.
     */
    private static final Pattern PHONE_SHAPED = Pattern.compile("^[0-9+ ().-]+$");

    private final Msisdns msisdns;

    /** The buyer this handle denotes — phone-keyed when it is a phone. */
    public AuthenticatedUser resolve(String handle) {
        String trimmed = handle == null ? "" : handle.trim();
        if (isPhoneShaped(trimmed)) {
            return PublicTestIdentity.buyerForPhone(msisdns.normalize(trimmed, "handle"));
        }
        return PublicTestIdentity.buyerFor(handle);
    }

    /**
     * The handle as it may appear in a log line. A phone is PII and is masked
     * to its last four digits (the fleet rule); an opaque test handle is the
     * caller's own label and stays readable, because "which demo basket was
     * that" is the whole reason the audit line exists.
     */
    public String loggable(String handle) {
        String trimmed = handle == null ? "" : handle.trim();
        return isPhoneShaped(trimmed) ? MsisdnMasking.mask(trimmed) : trimmed;
    }

    private static boolean isPhoneShaped(String trimmed) {
        return !trimmed.isEmpty()
                && PHONE_SHAPED.matcher(trimmed).matches()
                && trimmed.chars().anyMatch(Character::isDigit);
    }
}
