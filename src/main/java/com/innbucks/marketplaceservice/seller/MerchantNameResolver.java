package com.innbucks.marketplaceservice.seller;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Trading names for merchant ids, from whoever owns the registry.
 *
 * <p><b>Why this seam exists.</b> This service stores merchant IDS and no
 * merchant NAMES: {@code Listing.merchantId} and
 * {@code MarketOrderItem.merchantId} are loyalty ids copied off a JWT claim.
 * Every surface that should say who is selling — the buyer-facing badge, the
 * public seller profile, the admin trust queue, the finance payout report —
 * therefore had a UUID and nothing else, and rendered "Unnamed merchant".
 *
 * <p><b>Batch, deliberately.</b> Every caller is a PAGE: a catalogue page, a
 * moderation queue, a payout run. A one-id-at-a-time signature would make an
 * N+1 of all of them, and the one that matters is the catalogue — a shopper's
 * browse must not cost one network round trip per listing. Same discipline as
 * {@code ListingViewAssembler}'s grouped image query.
 *
 * <p><b>Best-effort, always.</b> A name is decoration: nothing in this service
 * DECIDES on one. Ownership comes from {@code merchant_id} columns, money from
 * the settlement ledger, authorization from the JWT. So an outage, an
 * unconfigured token or an unknown id all yield the same thing — no entry in
 * the map, which every caller already renders as "no name", exactly as today.
 * An implementation that throws would turn a cosmetic dependency into an
 * outage on the catalogue.
 */
public interface MerchantNameResolver {

    /**
     * Names for the ids the registry knows. An id with no entry in the result
     * means "no name available" and is never distinguishable from "the lookup
     * failed" — by design: the caller's next step is identical.
     */
    Map<UUID, String> namesFor(Collection<UUID> merchantIds);
}
