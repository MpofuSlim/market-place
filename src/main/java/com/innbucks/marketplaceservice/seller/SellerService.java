package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.dto.CollectionRequiredDetails;
import com.innbucks.marketplaceservice.seller.dto.CollectionSettingResponse;
import com.innbucks.marketplaceservice.seller.dto.PayoutDestinationRequest;
import com.innbucks.marketplaceservice.seller.dto.PayoutDestinationResponse;
import com.innbucks.marketplaceservice.seller.dto.SellerDecisionRequest;
import com.innbucks.marketplaceservice.seller.dto.SellerPageResponse;
import com.innbucks.marketplaceservice.seller.dto.SellerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Seller trust records and the SUPER_ADMIN approval queue (V8).
 *
 * <p><b>What this exists for.</b> A "seller" used to be nothing but a
 * merchant_id stamped on a listing, so the platform could neither vet one nor
 * tell a buyer it had — a shopper could not distinguish a long-standing
 * merchant from one that signed up an hour ago.
 *
 * <p><b>Two different lines, deliberately</b> (see {@link SellerStatus}):
 * APPROVED is what earns the buyer-facing badge; only REJECTED and SUSPENDED
 * stop a seller publishing. PENDING can trade — making it a hard gate would
 * turn this into an approval-queued marketplace, which is a product decision
 * about onboarding friction rather than a consequence of adding a trust record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SellerService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MarketplaceSellerRepository sellers;
    private final ListingRepository listings;
    private final AuditService auditService;
    private final Msisdns msisdns;
    private final ApplicationEventPublisher eventPublisher;
    private final MerchantNameResolver merchantNameResolver;
    private final DeliveryOnlyPolicy deliveryOnly;
    private final MarketplaceMetrics metrics;

    /** How many stranded listings a {@code collection_required} refusal names. */
    static final int STRANDED_LISTINGS_NAMED = 20;

    /**
     * The name to SHOW for each of these merchants: the operator-set trading
     * name when there is one, otherwise the organization registry's, best-effort.
     *
     * <h2>Why local wins</h2>
     * {@code display_name} is only ever written by {@code approve}, and an
     * operator who typed a name was correcting or choosing one deliberately —
     * a registry value must never overwrite that on screen. So the registry
     * fills GAPS and never overrules.
     *
     * <h2>Why only the gaps are asked for</h2>
     * An approved, named seller costs no network call at all. The lookup is
     * scoped to the ids that have no local name, which on a mature cell is a
     * shrinking minority — and it is the reason this can sit on the catalogue
     * path without making a browse depend on user-service being up.
     *
     * <h2>Why it is not transactional</h2>
     * The registry is another service's HTTP endpoint, and the resolver will
     * not call it while a transaction holds a pooled connection — inside one
     * it answers from its cache alone. So the reads that want fresh names
     * ({@link NameResolvingRead}) run their queries outside any surrounding
     * transaction, this one included; write paths that render a name get the
     * cached one.
     *
     * <p>Nothing here throws. A merchant with no name anywhere is simply
     * absent from the map, which every caller already renders as no name —
     * the behaviour before this existed.
     */
    @NameResolvingRead
    public Map<UUID, String> displayNames(List<UUID> merchantIds) {
        return displayNames(merchantIds, findAllByMerchantIds(merchantIds));
    }

    /**
     * {@link #displayNames(List)} for a caller that has ALREADY loaded the
     * seller rows — the catalogue assembler and the payout report both have
     * them in hand, and re-reading would undo the batching they exist for.
     */
    public Map<UUID, String> displayNames(List<UUID> merchantIds,
                                          Map<UUID, MarketplaceSeller> localByMerchant) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        List<UUID> unnamed = new ArrayList<>();
        for (UUID id : merchantIds) {
            if (id == null || names.containsKey(id)) {
                continue;
            }
            MarketplaceSeller local = localByMerchant == null ? null : localByMerchant.get(id);
            String own = local == null ? null : local.getDisplayName();
            if (own != null && !own.isBlank()) {
                names.put(id, own.trim());
            } else if (!unnamed.contains(id)) {
                unnamed.add(id);
            }
        }
        if (unnamed.isEmpty()) {
            return names;
        }
        // The registry fills gaps only, and a failure leaves them unfilled.
        names.putAll(merchantNameResolver.namesFor(unnamed));
        return names;
    }

    /**
     * The record for a merchant, creating a PENDING one on first sight.
     *
     * <p>Called from the listing-create path so a merchant enters the queue by
     * doing the thing that makes them a seller, rather than needing an admin to
     * pre-register them. Idempotent, and never overwrites a decision already
     * made — a suspended seller who somehow reaches create stays suspended.
     *
     * <p><b>Race-safe for a seller with NO row yet.</b> It used to be a
     * find-then-save: two concurrent first writes (a double-tapped "create
     * listing", two portal tabs saving a payout destination) both missed the
     * row, both inserted, and the loser 500'd on the primary key — leaving a
     * second {@code SELLER_REGISTERED} on the audit chain for a registration
     * that rolled back. The row is now created by
     * {@code INSERT … ON CONFLICT DO NOTHING}: the loser's insert waits for the
     * winner and then does nothing, and only the call that actually created the
     * row audits the registration. The read that follows runs after that wait,
     * so under READ COMMITTED it sees the winner's committed row.
     */
    @Transactional
    public MarketplaceSeller ensureExists(UUID merchantId) {
        if (sellers.insertIfAbsent(merchantId, Instant.now()) == 1) {
            log.info("Seller record created merchantId={} status=PENDING", merchantId);
            auditService.record(AuditEventType.SELLER_REGISTERED, null, merchantId.toString(),
                    Map.of("status", SellerStatus.PENDING.name()));
        }
        return sellers.findById(merchantId).orElseThrow(() -> new IllegalStateException(
                "Seller record " + merchantId + " missing straight after insert-if-absent"));
    }

    /**
     * Makes sure the seller's trust record exists and takes its row lock for
     * the rest of the caller's transaction — for writes that keep a per-seller
     * invariant only this service can enforce (exactly one default collection
     * point). A lock needs a row to lock, which is why {@link #ensureExists}
     * must be race-safe first: with a find-then-save, two first taps both
     * inserted the seller and the loser failed before the lock could serialise
     * anything.
     */
    @Transactional
    public void ensureExistsAndLock(UUID merchantId) {
        ensureExists(merchantId);
        sellers.lockForUpdate(merchantId);
    }

    /**
     * Whether this merchant may publish. A merchant with NO record yet is
     * allowed: the record is created on their first listing, and treating a
     * missing row as a refusal would block anyone whose row has not been
     * written yet — failing closed on an absence that means "new", not "barred".
     * REJECTED and SUSPENDED are the refusals, and both are explicit rows.
     */
    @Transactional(readOnly = true)
    public boolean canPublish(UUID merchantId) {
        return sellers.findById(merchantId)
                .map(s -> s.getStatus().canPublish())
                .orElse(true);
    }

    /** Batch lookup for the buyer-facing badge — one query per page of
     *  listings, never one per listing (the assembler's standing discipline). */
    @Transactional(readOnly = true)
    public Map<UUID, MarketplaceSeller> findAllByMerchantIds(List<UUID> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MarketplaceSeller> byId = new HashMap<>();
        for (MarketplaceSeller s : sellers.findAllById(merchantIds)) {
            byId.put(s.getMerchantId(), s);
        }
        return byId;
    }

    /** The queue. {@code status} null lists every seller, oldest first. */
    @NameResolvingRead
    public SellerPageResponse list(SellerStatus status, int page, int size) {
        if (page < 0) {
            throw ApiException.badRequest("invalid_page", "page must be >= 0");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("invalid_size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        PageRequest pageable = PageRequest.of(page, size);
        Page<MarketplaceSeller> result = status == null
                ? sellers.findAllByOrderByCreatedAtAsc(pageable)
                : sellers.findByStatusOrderByCreatedAtAsc(status, pageable);
        // ONE batch for the page's names, not one call per row. The rows are
        // already loaded, so this resolves gaps only — a queue of sellers the
        // operator has already named costs nothing.
        Map<UUID, MarketplaceSeller> loaded = new HashMap<>();
        for (MarketplaceSeller row : result.getContent()) {
            loaded.put(row.getMerchantId(), row);
        }
        Map<UUID, String> names = displayNames(List.copyOf(loaded.keySet()), loaded);
        return SellerPageResponse.from(
                result.map(row -> SellerResponse.from(row, names.get(row.getMerchantId()))));
    }

    /** Vouch for a seller: the only status that earns the badge. */
    @Transactional
    public SellerResponse approve(AuthenticatedUser caller, UUID merchantId, SellerDecisionRequest body) {
        return decide(caller, merchantId, SellerStatus.APPROVED, body, false, seller -> {
            String name = trimToNull(body == null ? null : body.displayName());
            if (name != null) {
                seller.setDisplayName(name);
            }
            return 0;
        });
    }

    /** Refuse a seller. They keep any DRAFT work but cannot publish. */
    @Transactional
    public SellerResponse reject(AuthenticatedUser caller, UUID merchantId, SellerDecisionRequest body) {
        return decide(caller, merchantId, SellerStatus.REJECTED, body, true, seller -> 0);
    }

    /**
     * Stop a seller trading AND take their live listings down in the same
     * transaction — a suspension that left goods on sale would mean nothing.
     */
    @Transactional
    public SellerResponse suspend(AuthenticatedUser caller, UUID merchantId, SellerDecisionRequest body) {
        return decide(caller, merchantId, SellerStatus.SUSPENDED, body, true,
                seller -> listings.deactivateActiveListingsOf(merchantId, Instant.now()));
    }

    /**
     * Lift a suspension or rejection, back to APPROVED.
     *
     * <p>Exists because suspend with no way back is a trap: without it an admin
     * who suspends by mistake has no remedy inside the platform. Deliberately
     * does NOT re-publish the listings it took down — the seller chooses what
     * goes back on sale, and silently re-listing goods an admin had removed is
     * the wrong default.
     */
    @Transactional
    public SellerResponse reinstate(AuthenticatedUser caller, UUID merchantId, SellerDecisionRequest body) {
        MarketplaceSeller seller = require(merchantId);
        if (seller.getStatus() != SellerStatus.SUSPENDED && seller.getStatus() != SellerStatus.REJECTED) {
            throw ApiException.conflict("seller_not_suspended",
                    "Only a SUSPENDED or REJECTED seller can be reinstated (status=" + seller.getStatus() + ")");
        }
        return decide(caller, merchantId, SellerStatus.APPROVED, body, false, s -> 0);
    }

    // ------------------------------------------------------------------
    // Payout destination (V13) — where this seller's released money goes
    // ------------------------------------------------------------------

    /**
     * Reads a seller's destination, creating the trust record if this is the
     * first thing they ever do here.
     *
     * <p>{@code ensureExists} rather than a 404: a merchant with a valid
     * {@code merchantId} claim IS a seller, and asking them for bank details
     * before they have listed anything is a perfectly ordinary onboarding
     * order. Refusing would make the screen unreachable for exactly the
     * sellers who have not started yet.
     */
    @Transactional
    public PayoutDestinationResponse payoutDestination(UUID merchantId) {
        return PayoutDestinationResponse.from(ensureExists(merchantId));
    }

    /**
     * Sets or replaces a seller's payout destination.
     *
     * <p><b>Replace, never merge.</b> The request carries a whole destination
     * and overwrites all five columns. A partial update is precisely how a row
     * ends up naming one rail with another's account behind it — the state
     * {@code chk_seller_payout_destination} exists to make unrepresentable —
     * and "change just my account number" is not a smaller action than
     * "change where my money goes", it is the same one.
     *
     * <p>The per-method fields are validated HERE rather than by Bean
     * Validation, which cannot express "required depending on another field"
     * without losing the field name the app needs to highlight.
     */
    @Transactional
    public PayoutDestinationResponse setPayoutDestination(AuthenticatedUser caller,
                                                          UUID merchantId,
                                                          PayoutDestinationRequest body,
                                                          boolean bySeller) {
        // Validate FIRST: a refused request must not create (and audit) a
        // seller record it then rolls back.
        String accountName = requireText(body.accountName(), "accountName");

        String msisdn = null;
        String bankName = null;
        String accountNumber = null;
        switch (body.method()) {
            case MOBILE_MONEY -> msisdn = msisdns.normalize(body.msisdn(), "msisdn");
            case BANK -> {
                bankName = requireText(body.bankName(), "bankName");
                accountNumber = requireText(body.accountNumber(), "accountNumber");
            }
        }
        MarketplaceSeller seller = ensureExists(merchantId);

        boolean replaced = seller.hasPayoutDestination();
        seller.setPayoutMethod(body.method());
        seller.setPayoutAccountName(accountName);
        seller.setPayoutMsisdn(msisdn);
        seller.setPayoutBankName(bankName);
        seller.setPayoutAccountNumber(accountNumber);
        seller.setPayoutUpdatedAt(Instant.now());
        seller.setPayoutUpdatedBy(adminUuid(caller));
        sellers.save(seller);

        // Method and whether it replaced something, never the account itself —
        // enough to investigate a redirected payout with, without putting an
        // account number in one more place.
        Map<String, Object> meta = new HashMap<>();
        meta.put("method", body.method().name());
        meta.put("replacedExisting", replaced);
        meta.put("bySeller", bySeller);
        auditService.record(AuditEventType.SELLER_PAYOUT_DESTINATION_CHANGED,
                caller == null ? null : caller.uuid(), merchantId.toString(), meta);

        // AFTER_COMMIT + async on the other side: the seller must never be
        // warned about a change that then rolled back, and a dead SMS gateway
        // must never look like a refused update.
        eventPublisher.publishEvent(new PayoutDestinationChanged(
                merchantId, body.method(), replaced, bySeller));

        log.info("Payout destination set merchantId={} method={} replacedExisting={} bySeller={}",
                merchantId, body.method(), replaced, bySeller);
        return PayoutDestinationResponse.from(seller);
    }

    /** Names the offending field so the app can highlight the right input —
     *  what a class-level Bean Validation constraint could not have done. */
    private static String requireText(String value, String field) {
        String trimmed = trimToNull(TextSanitizer.sanitize(value));
        if (trimmed == null) {
            throw ApiException.badRequest("payout_field_required",
                    field + " is required for this payout method");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // Collection (V20) — whether buyers may collect from this seller
    // ------------------------------------------------------------------

    /**
     * The seller's collection setting. A read never registers a seller: a
     * merchant with no record collects and has never changed it, which is
     * exactly what an absent row already says.
     */
    @Transactional(readOnly = true)
    public CollectionSettingResponse collectionSetting(UUID merchantId) {
        return CollectionSettingResponse.of(sellers.findById(merchantId).orElse(null));
    }

    /**
     * Whether this merchant is DELIVERY-ONLY — for the listing gates, which
     * ask it inside a listing write. A plain read that takes no lock, on
     * purpose: a listing write that also took the seller's row lock would
     * order the two locks the opposite way from the seller-level writes
     * ({@code suspend} updates the seller and takes every listing down in one
     * transaction) — the shape Postgres aborts as a deadlock. Advisory for the
     * same reason: a seller switching collection off at that very moment can
     * still let one item through, and checkout refuses what nobody can get to
     * the buyer.
     */
    @Transactional(readOnly = true)
    public boolean isDeliveryOnly(UUID merchantId) {
        return sellers.findById(merchantId)
                .map(s -> !s.isCollectionEnabled())
                .orElse(false);
    }

    /**
     * Turns collection on or off for a seller.
     *
     * <p><b>Every refusal comes before any write</b>, so a refused request
     * never registers (and audits) a seller it then rolls back: turning
     * collection OFF needs the cell switch (422 {@code delivery_only_disabled}),
     * a market that delivers (422 {@code delivery_not_offered}) and no item on
     * sale that could only be collected (409 {@code collection_required},
     * naming them). Turning it back ON is never gated.
     *
     * <p><b>A request that changes nothing writes nothing and refuses
     * nothing</b> — no row, no stamp, no audit, no gate — judged on the
     * EFFECTIVE value, where a missing record reads as collecting. Otherwise a seller's first "keep collection on"
     * would register them and put {@code SELLER_REGISTERED} on the audit chain
     * for a setting that did not move.
     *
     * <p><b>Stranded listings are refused, never fixed for the seller.</b>
     * Deactivating them would take goods off sale because of a settings
     * change, and nothing would put them back.
     *
     * <p>The write goes through the one bulk UPDATE
     * ({@link MarketplaceSellerRepository#setCollectionEnabled}): the entity
     * has no {@code @Version}, and the approval and payout paths save the
     * whole row, so the column is read-only on the entity.
     */
    @Transactional
    public CollectionSettingResponse setCollectionEnabled(AuthenticatedUser caller,
                                                          UUID merchantId,
                                                          boolean enabled,
                                                          boolean bySeller) {
        // The no-op is judged FIRST, and it is a pure read. Re-saving the value a
        // seller already has answers 200 however the gates now stand: a seller
        // who went delivery-only keeps that when the cell switch is turned off
        // (application.yaml says so), and a settings form that re-saves the
        // current state must not be told that turning collection off "is not
        // available yet" when it already is off.
        MarketplaceSeller current = sellers.findById(merchantId).orElse(null);
        if (MarketplaceSeller.collects(current) == enabled) {
            return CollectionSettingResponse.of(current);
        }
        if (!enabled) {
            requireDeliveryOnlyAllowed(merchantId);
        }

        // A real change. The lock serialises two concurrent toggles for one
        // seller; the re-read is the value as the DATABASE now holds it, not
        // the copy loaded above (the persistence context would hand that back
        // unchanged), so the audit's "previous" is exact.
        ensureExistsAndLock(merchantId);
        MarketplaceSellerRepository.CollectionState locked = sellers.collectionStateOf(merchantId);
        boolean previous = locked == null || !Boolean.FALSE.equals(locked.getCollectionEnabled());
        if (previous == enabled) {
            // A concurrent request made the same change first: nothing moves.
            return new CollectionSettingResponse(previous,
                    locked == null ? null : locked.getCollectionUpdatedAt());
        }
        Instant now = Instant.now();
        sellers.setCollectionEnabled(merchantId, enabled, now, adminUuid(caller));

        // The setting and who moved it — nothing typed, so nothing free-text.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("merchantId", merchantId.toString());
        meta.put("collectionEnabled", enabled);
        meta.put("previous", previous);
        meta.put("bySeller", bySeller);
        auditService.record(AuditEventType.SELLER_COLLECTION_CHANGED,
                caller == null ? null : caller.uuid(), merchantId.toString(), meta);
        metrics.sellerCollectionChanged(enabled);
        log.info("Seller collection changed merchantId={} collectionEnabled={} bySeller={}",
                merchantId, enabled, bySeller);
        return new CollectionSettingResponse(enabled, now);
    }

    /** The three refusals of a move to delivery-only, cheapest first; reads only. */
    private void requireDeliveryOnlyAllowed(UUID merchantId) {
        if (!deliveryOnly.enabled()) {
            throw ApiException.unprocessable("delivery_only_disabled",
                    "Turning collection off is not available yet");
        }
        if (!deliveryOnly.deliveryOffered()) {
            throw ApiException.unprocessable("delivery_not_offered",
                    "Delivery is not offered in this market, so collection cannot be turned off");
        }
        // One more than we name, so the refusal can say whether it named them all.
        List<ListingRepository.ListingTitle> stranded = listings.findActiveWithoutDeliveryTowns(
                merchantId, Limit.of(STRANDED_LISTINGS_NAMED + 1));
        if (!stranded.isEmpty()) {
            List<CollectionRequiredDetails.StrandedListing> named = stranded.stream()
                    .limit(STRANDED_LISTINGS_NAMED)
                    .map(row -> new CollectionRequiredDetails.StrandedListing(row.getId(), row.getTitle()))
                    .toList();
            throw ApiException.conflict("collection_required",
                            "Some of your items on sale can only be collected - add delivery towns "
                                    + "to them or take them off sale first")
                    .withDetails(new CollectionRequiredDetails(
                            named, stranded.size() > STRANDED_LISTINGS_NAMED));
        }
    }

    // -------------------------------------------------------------------------

    /**
     * One transition path so every decision is recorded the same way: status,
     * who, when, why — and an audit row. {@code noteRequired} is the
     * reject/suspend rule; {@code sideEffect} returns a count folded into the
     * audit metadata (listings taken down on suspend).
     */
    private SellerResponse decide(AuthenticatedUser caller,
                                  UUID merchantId,
                                  SellerStatus to,
                                  SellerDecisionRequest body,
                                  boolean noteRequired,
                                  Function<MarketplaceSeller, Integer> sideEffect) {
        String note = trimToNull(body == null ? null : body.note());
        if (noteRequired && note == null) {
            // A seller told only "no" cannot fix anything, and a second admin
            // cannot see what a colleague already decided.
            throw ApiException.badRequest("note_required",
                    "A note is required when rejecting or suspending a seller");
        }
        MarketplaceSeller seller = require(merchantId);
        SellerStatus from = seller.getStatus();
        if (from == to && to != SellerStatus.APPROVED) {
            // Re-approving is allowed (it can update the display name); a
            // repeated reject/suspend is a no-op an admin should be told about.
            throw ApiException.conflict("seller_already_" + to.name().toLowerCase(),
                    "Seller is already " + to);
        }

        seller.setStatus(to);
        seller.setDecidedBy(adminUuid(caller));
        seller.setDecisionNote(note);
        seller.setDecidedAt(Instant.now());
        int affected = sideEffect.apply(seller);
        sellers.save(seller);

        Map<String, Object> meta = new HashMap<>();
        meta.put("from", from.name());
        meta.put("to", to.name());
        if (note != null) {
            meta.put("note", note);
        }
        if (affected > 0) {
            meta.put("listingsDeactivated", affected);
        }
        auditService.record(AuditEventType.SELLER_STATUS_CHANGED,
                caller == null ? null : caller.uuid(), merchantId.toString(), meta);
        log.info("Seller status changed merchantId={} from={} to={} listingsDeactivated={}",
                merchantId, from, to, affected);
        return SellerResponse.from(seller);
    }

    /** The admin's uuid, tolerating a legacy/test token whose uuid is not a
     *  UUID — attribution is worth recording, but never worth failing a
     *  moderation action over. */
    private static UUID adminUuid(AuthenticatedUser caller) {
        if (caller == null || caller.uuid() == null) {
            return null;
        }
        try {
            return UUID.fromString(caller.uuid());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private MarketplaceSeller require(UUID merchantId) {
        return sellers.findById(merchantId)
                .orElseThrow(() -> ApiException.notFound("seller_not_found",
                        "No seller record for merchant " + merchantId));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
