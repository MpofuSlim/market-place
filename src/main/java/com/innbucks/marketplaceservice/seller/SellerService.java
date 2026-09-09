package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.dto.SellerDecisionRequest;
import com.innbucks.marketplaceservice.seller.dto.SellerPageResponse;
import com.innbucks.marketplaceservice.seller.dto.SellerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
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

    /**
     * The record for a merchant, creating a PENDING one on first sight.
     *
     * <p>Called from the listing-create path so a merchant enters the queue by
     * doing the thing that makes them a seller, rather than needing an admin to
     * pre-register them. Idempotent, and never overwrites a decision already
     * made — a suspended seller who somehow reaches create stays suspended.
     */
    @Transactional
    public MarketplaceSeller ensureExists(UUID merchantId) {
        return sellers.findById(merchantId).orElseGet(() -> {
            MarketplaceSeller created = sellers.save(MarketplaceSeller.builder()
                    .merchantId(merchantId)
                    .status(SellerStatus.PENDING)
                    .createdAt(Instant.now())
                    .build());
            log.info("Seller record created merchantId={} status=PENDING", merchantId);
            auditService.record(AuditEventType.SELLER_REGISTERED, null, merchantId.toString(),
                    Map.of("status", SellerStatus.PENDING.name()));
            return created;
        });
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
    @Transactional(readOnly = true)
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
        return SellerPageResponse.from(result.map(SellerResponse::from));
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
