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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seller-side listing lifecycle. Every method resolves the merchant scope from
 * the caller's JWT claims (never a request body) and enforces ownership before
 * touching a row; all free text passes {@link TextSanitizer} before storage.
 *
 * <p><b>SUPER_ADMIN exception (owner-approved fleet oversight):</b> a
 * SUPER_ADMIN caller bypasses the ownership check (may manage ANY merchant's
 * listing), needs no {@code merchantId} claim, sees every merchant's listings
 * on the "mine" read, and — the one deliberate exception to
 * merchant-scope-from-JWT — may CREATE on behalf of a merchant by naming the
 * target in {@link ListingCreateRequest#merchantId()}. For MERCHANT_ADMIN
 * callers that request field is refused whenever it differs from their claim,
 * so the invariant stays intact for merchants.
 */
@Service
public class ListingService {

    /** Server-side money/stock bounds, mirrored by the DTOs' Bean Validation.
     *  Re-checked here so a future programmatic caller can't bypass them. */
    static final long MIN_PRICE_CENTS = 1;
    static final long MAX_PRICE_CENTS = 100_000_000;
    static final int MIN_STOCK_QTY = 0;
    static final int MAX_STOCK_QTY = 1_000_000;

    /** Max stored image size — mirrors event-service's banner cap. The servlet
     *  multipart limit (spring.servlet.multipart.max-file-size) enforces the
     *  same 10 MB before the controller runs; this re-check keeps the guard
     *  even for a programmatic caller, and GlobalExceptionHandler maps the
     *  container's rejection to the same 400 image_too_large. */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    // GIF is deliberately NOT accepted (event-service stance): listing images
    // are static product shots and animated media is a moderation/abuse
    // surface — do not add image/gif here without also restoring the GIF
    // magic-byte branch in isSupportedImageSignature.
    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );

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
        UUID merchantId = resolveCreateMerchantId(caller, request);
        validateRanges(request.priceCents(), request.stockQty());
        // Row-volume abuse guard. ARCHIVED rows still count: listings are never
        // physically deleted, so this caps the merchant's total row footprint.
        // The check-then-insert race under concurrency is accepted — the cap is
        // an abuse guard, not an invariant. Applies to the TARGET merchant on
        // SUPER_ADMIN on-behalf creation too.
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
        validateRanges(request.priceCents(), request.stockQty());
        Listing listing = managedListing(caller, listingId);
        listing.setTitle(requiredTitle(request.title()));
        listing.setDescription(sanitizedOrNull(request.description()));
        listing.setCategory(sanitizedOrNull(request.category()));
        listing.setPriceCents(request.priceCents());
        listing.setStockQty(request.stockQty());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_UPDATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "priceCents", listing.getPriceCents(),
                        "stockQty", listing.getStockQty()));
        return ListingResponse.from(listing);
    }

    @Transactional
    public ListingResponse changeStatus(AuthenticatedUser caller, UUID listingId, ListingStatusRequest request) {
        Listing listing = managedListing(caller, listingId);
        ListingStatus from = listing.getStatus();
        listing.setStatus(request.status());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_STATUS_CHANGED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "from", from.name(),
                        "to", listing.getStatus().name()));
        return ListingResponse.from(listing);
    }

    /**
     * Stores/replaces the listing image (bytes + content type). Validation is
     * event-service's banner discipline: allow-listed content type AND
     * magic-byte signature (the declared type is attacker-controlled), size
     * capped at {@link #MAX_IMAGE_BYTES}. Owner-or-SUPER_ADMIN via
     * {@link #managedListing}.
     */
    @Transactional
    public ListingResponse uploadImage(AuthenticatedUser caller, UUID listingId, MultipartFile file) {
        Listing listing = managedListing(caller, listingId);
        applyImage(listing, file);
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_IMAGE_UPDATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "contentType", listing.getImageContentType(),
                        "sizeBytes", listing.getImageBytes().length));
        return ListingResponse.from(listing);
    }

    /** Clears both image columns. Owner-or-SUPER_ADMIN. Deleting an absent
     *  image is a no-op 200 — the end state is identical. */
    @Transactional
    public ListingResponse deleteImage(AuthenticatedUser caller, UUID listingId) {
        Listing listing = managedListing(caller, listingId);
        listing.setImageBytes(null);
        listing.setImageContentType(null);
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_IMAGE_DELETED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString()));
        return ListingResponse.from(listing);
    }

    /**
     * "My listings" — for a MERCHANT_ADMIN, the caller's merchant's listings
     * (every status; the {@code merchantIdFilter} is IGNORED — a merchant can
     * only ever see their own). For SUPER_ADMIN: ALL listings, any status,
     * optionally narrowed to one merchant via the filter.
     */
    @Transactional(readOnly = true)
    public ListingPageResponse listMine(AuthenticatedUser caller, int page, int size,
                                        UUID merchantIdFilter) {
        // Same clamp as the public catalog: oversized sizes shrink, never error.
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, CatalogService.MAX_PAGE_SIZE), NEWEST_FIRST);
        Page<Listing> result;
        if (caller.isSuperAdmin()) {
            result = merchantIdFilter == null
                    ? listingRepository.findAll(pageable)
                    : listingRepository.findByMerchantId(merchantIdFilter, pageable);
        } else {
            result = listingRepository.findByMerchantId(requireMerchantId(caller), pageable);
        }
        return ListingPageResponse.from(result.map(ListingResponse::from));
    }

    /**
     * Merchant scope for CREATE. MERCHANT_ADMIN: always the JWT claim; the
     * optional request {@code merchantId} is tolerated only when it EQUALS the
     * claim (422 {@code merchant_scope_mismatch} otherwise), so the
     * merchant-scope-from-JWT invariant stays intact for merchants.
     * SUPER_ADMIN is the deliberate, owner-approved exception: the admin token
     * carries no merchant claim, so on-behalf creation REQUIRES the request
     * field (400 {@code merchant_id_required} when absent).
     */
    private static UUID resolveCreateMerchantId(AuthenticatedUser caller, ListingCreateRequest request) {
        if (caller.isSuperAdmin()) {
            if (request.merchantId() == null) {
                throw ApiException.badRequest("merchant_id_required",
                        "merchantId is required when a SUPER_ADMIN creates a listing on behalf of a merchant");
            }
            return request.merchantId();
        }
        UUID claimed = requireMerchantId(caller);
        if (request.merchantId() != null && !request.merchantId().equals(claimed)) {
            throw ApiException.unprocessable("merchant_scope_mismatch",
                    "merchantId in the request does not match the caller's merchant scope");
        }
        return claimed;
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

    /**
     * Loads the listing the caller may administer. MERCHANT_ADMIN must own it
     * (403 {@code listing_not_owned}; a missing/malformed merchant claim is
     * still 403 {@code merchant_scope_missing}, checked BEFORE the row lookup
     * so an unscoped token learns nothing about which ids exist). SUPER_ADMIN
     * bypasses both — fleet oversight manages any merchant's listing and
     * carries no merchant claim.
     */
    private Listing managedListing(AuthenticatedUser caller, UUID listingId) {
        UUID merchantId = caller.isSuperAdmin() ? null : requireMerchantId(caller);
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> ApiException.notFound("listing_not_found", "Listing not found"));
        if (merchantId != null && !listing.getMerchantId().equals(merchantId)) {
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

    // ------------------------------------------------------------------
    // Image validation (event-service applyBanner, error codes ours)
    // ------------------------------------------------------------------

    private static void applyImage(Listing listing, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("image_required",
                    "An image file part named 'image' is required");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("image_too_large",
                    "That image is too large. Please use one under 10 MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("unsupported_image_type",
                    "Please upload a JPG, PNG, or WEBP image.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // Genuine server-side I/O failure reading the upload stream — let
            // the catch-all in GlobalExceptionHandler return 500 with a
            // sanitised message. Wrapped so the IOException doesn't escape
            // the @Transactional boundary unchecked.
            throw new IllegalStateException("Failed to read listing image", e);
        }
        // OWASP A03: the declared Content-Type is attacker-controlled, so confirm
        // the payload really is one of the image formats we accept by matching its
        // magic-byte signature before we store (and later serve) it — this rejects
        // an HTML/script payload smuggled under an image/* header.
        if (!isSupportedImageSignature(bytes)) {
            throw ApiException.badRequest("unsupported_image_type",
                    "Please upload a valid image file (JPG, PNG, or WEBP).");
        }
        listing.setImageBytes(bytes);
        listing.setImageContentType(contentType.toLowerCase(Locale.ROOT));
    }

    // Magic-byte sniff for the three image formats we allow (GIF is rejected —
    // see ALLOWED_IMAGE_CONTENT_TYPES). Signature-only (no full image decode) —
    // enough to reject non-image payloads without pulling in an image library.
    // Copied faithfully from event-service's isSupportedImageSignature.
    static boolean isSupportedImageSignature(byte[] b) {
        if (b == null) {
            return false;
        }
        // JPEG: FF D8 FF
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50 && (b[2] & 0xFF) == 0x4E
                && (b[3] & 0xFF) == 0x47 && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return true;
        }
        // WEBP: bytes 0-3 "RIFF" (52 49 46 46) AND bytes 8-11 "WEBP" (57 45 42 50)
        if (b.length >= 12
                && (b[0] & 0xFF) == 0x52 && (b[1] & 0xFF) == 0x49 && (b[2] & 0xFF) == 0x46
                && (b[3] & 0xFF) == 0x46
                && (b[8] & 0xFF) == 0x57 && (b[9] & 0xFF) == 0x45 && (b[10] & 0xFF) == 0x42
                && (b[11] & 0xFF) == 0x50) {
            return true;
        }
        return false;
    }
}
