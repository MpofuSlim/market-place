package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Seller-side listing lifecycle. Every method resolves the merchant scope from
 * the caller's JWT claims (never a request body) and enforces ownership before
 * touching a row; all free text passes {@link TextSanitizer} before storage.
 */
@Service
public class ListingService {

    /** Server-side money/stock bounds, mirrored by the DTOs' Bean Validation.
     *  Re-checked here so a future programmatic caller can't bypass them. */
    static final long MIN_PRICE_CENTS = 1;
    static final long MAX_PRICE_CENTS = 100_000_000;
    static final int MIN_STOCK_QTY = 0;
    static final int MAX_STOCK_QTY = 1_000_000;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ListingRepository listingRepository;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final String cellCurrency;
    private final int maxPerMerchant;

    public ListingService(ListingRepository listingRepository,
                          AuditService auditService,
                          MarketplaceMetrics metrics,
                          @Value("${innbucks.currency}") String cellCurrency,
                          @Value("${marketplace.listing.max-per-merchant}") int maxPerMerchant) {
        this.listingRepository = listingRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.cellCurrency = cellCurrency;
        this.maxPerMerchant = maxPerMerchant;
    }

    @Transactional
    public ListingResponse create(AuthenticatedUser caller, ListingCreateRequest request) {
        UUID merchantId = requireMerchantId(caller);
        validateRanges(request.priceCents(), request.stockQty());
        // Row-volume abuse guard. ARCHIVED rows still count: listings are never
        // physically deleted, so this caps the merchant's total row footprint.
        // The check-then-insert race under concurrency is accepted — the cap is
        // an abuse guard, not an invariant.
        if (listingRepository.countByMerchantId(merchantId) >= maxPerMerchant) {
            throw ApiException.conflict("listing_limit_reached", "Merchant listing limit reached");
        }
        Instant now = Instant.now();
        Listing listing = Listing.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .shopId(optionalShopId(caller))
                .title(requiredTitle(request.title()))
                .description(sanitizedOrNull(request.description()))
                .category(sanitizedOrNull(request.category()))
                .priceCents(request.priceCents())
                .currency(cellCurrency)
                .stockQty(request.stockQty())
                .status(ListingStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build();
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_CREATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", merchantId.toString(),
                        "priceCents", listing.getPriceCents(),
                        "stockQty", listing.getStockQty(),
                        "status", listing.getStatus().name()));
        metrics.listingCreated();
        return ListingResponse.from(listing);
    }

    @Transactional
    public ListingResponse update(AuthenticatedUser caller, UUID listingId, ListingUpdateRequest request) {
        UUID merchantId = requireMerchantId(caller);
        validateRanges(request.priceCents(), request.stockQty());
        Listing listing = ownedListing(merchantId, listingId);
        listing.setTitle(requiredTitle(request.title()));
        listing.setDescription(sanitizedOrNull(request.description()));
        listing.setCategory(sanitizedOrNull(request.category()));
        listing.setPriceCents(request.priceCents());
        listing.setStockQty(request.stockQty());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_UPDATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", merchantId.toString(),
                        "priceCents", listing.getPriceCents(),
                        "stockQty", listing.getStockQty()));
        return ListingResponse.from(listing);
    }

    @Transactional
    public ListingResponse changeStatus(AuthenticatedUser caller, UUID listingId, ListingStatusRequest request) {
        UUID merchantId = requireMerchantId(caller);
        Listing listing = ownedListing(merchantId, listingId);
        ListingStatus from = listing.getStatus();
        listing.setStatus(request.status());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_STATUS_CHANGED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", merchantId.toString(),
                        "from", from.name(),
                        "to", listing.getStatus().name()));
        return ListingResponse.from(listing);
    }

    @Transactional(readOnly = true)
    public ListingPageResponse listMine(AuthenticatedUser caller, int page, int size) {
        UUID merchantId = requireMerchantId(caller);
        // Same clamp as the public catalog: oversized sizes shrink, never error.
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, CatalogService.MAX_PAGE_SIZE), NEWEST_FIRST);
        return ListingPageResponse.from(
                listingRepository.findByMerchantId(merchantId, pageable).map(ListingResponse::from));
    }

    /**
     * Merchant scope comes from the JWT, never from a request body. Absent or
     * malformed {@code merchantId} claim = the caller has no usable merchant
     * scope — a 403, not a 500.
     */
    private static UUID requireMerchantId(AuthenticatedUser caller) {
        String claim = caller.merchantId();
        if (claim == null || claim.isBlank()) {
            throw ApiException.forbidden("merchant_scope_missing", "Caller token carries no merchant scope");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("merchant_scope_missing", "Caller token carries no merchant scope");
        }
    }

    /** shopId is optional scoping metadata — absent/malformed maps to null
     *  rather than failing the write. */
    private static UUID optionalShopId(AuthenticatedUser caller) {
        String claim = caller.shopId();
        if (claim == null || claim.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Listing ownedListing(UUID merchantId, UUID listingId) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> ApiException.notFound("listing_not_found", "Listing not found"));
        if (!listing.getMerchantId().equals(merchantId)) {
            throw ApiException.forbidden("listing_not_owned",
                    "Listing does not belong to the caller's merchant");
        }
        return listing;
    }

    private static void validateRanges(long priceCents, int stockQty) {
        if (priceCents < MIN_PRICE_CENTS || priceCents > MAX_PRICE_CENTS) {
            throw ApiException.badRequest("price_out_of_range",
                    "priceCents must be between 1 and 100000000");
        }
        if (stockQty < MIN_STOCK_QTY || stockQty > MAX_STOCK_QTY) {
            throw ApiException.badRequest("stock_out_of_range",
                    "stockQty must be between 0 and 1000000");
        }
    }

    /** A title made entirely of HTML (e.g. {@code <img src=x>}) sanitizes to
     *  nothing — reject it rather than store a blank NOT NULL column. */
    private static String requiredTitle(String rawTitle) {
        String sanitized = TextSanitizer.sanitize(rawTitle);
        if (sanitized == null || sanitized.isBlank()) {
            throw ApiException.badRequest("title_invalid", "Title must not be empty after sanitization");
        }
        return sanitized;
    }

    private static String sanitizedOrNull(String raw) {
        String sanitized = TextSanitizer.sanitize(raw);
        return (sanitized == null || sanitized.isBlank()) ? null : sanitized;
    }
}
