package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p><b>Gallery invariant (V3):</b> a listing has 0–{@value #MAX_GALLERY_IMAGES}
 * images; whenever it has ANY images, exactly ONE is primary (the partial
 * unique index is the DB backstop). Every mutation here preserves that:
 * create marks the first uploaded file primary, adding to an empty gallery
 * promotes the sole image, deleting the primary promotes the lowest-position
 * survivor, and the primary swap runs demote-then-mark as ordered bulk
 * statements. The <b>publish gate</b> leans on it: a status change TO ACTIVE
 * requires a primary image (422 {@code primary_image_required}) — drafts may
 * be imageless, pre-existing ACTIVE rows are untouched.
 */
@Service
public class ListingService {

    /** Server-side money/stock bounds, mirrored by the DTOs' Bean Validation.
     *  Re-checked here so a future programmatic caller can't bypass them. */
    static final long MIN_PRICE_CENTS = 1;
    static final long MAX_PRICE_CENTS = 100_000_000;
    static final int MIN_STOCK_QTY = 0;
    static final int MAX_STOCK_QTY = 1_000_000;

    /** Gallery caps: one primary + up to nine additional images. */
    static final int MAX_GALLERY_IMAGES = 10;
    static final int MAX_ADDITIONAL_IMAGES = MAX_GALLERY_IMAGES - 1;

    /** Fallback taxonomy code — the V4 backfill value and the default when a
     *  request omits categoryCode. Seeded by the migration, so it always
     *  exists. */
    static final String DEFAULT_CATEGORY_CODE = "other";

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
    private final ListingImageRepository listingImageRepository;
    private final CategoryRepository categoryRepository;
    private final ListingViewAssembler assembler;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final String cellCurrency;
    private final int maxPerMerchant;

    public ListingService(ListingRepository listingRepository,
                          ListingImageRepository listingImageRepository,
                          CategoryRepository categoryRepository,
                          ListingViewAssembler assembler,
                          AuditService auditService,
                          MarketplaceMetrics metrics,
                          ApplicationEventPublisher eventPublisher,
                          @Value("${innbucks.currency}") String cellCurrency,
                          @Value("${marketplace.listing.max-per-merchant}") int maxPerMerchant) {
        this.listingRepository = listingRepository;
        this.listingImageRepository = listingImageRepository;
        this.categoryRepository = categoryRepository;
        this.assembler = assembler;
        this.auditService = auditService;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.cellCurrency = cellCurrency;
        this.maxPerMerchant = maxPerMerchant;
    }

    @Transactional
    public ListingResponse create(AuthenticatedUser caller, ListingCreateRequest request) {
        return create(caller, request, null, null);
    }

    /**
     * Create with an optional inline gallery (the multipart variant — the
     * event-service one-shot create-with-banner shape, extended to V3's
     * multi-image model). {@code primaryImage} null = no {@code image} part
     * sent; {@code additionalImages} carries the repeated {@code images}
     * parts (max {@value #MAX_ADDITIONAL_IMAGES}). The FIRST uploaded file
     * becomes the gallery's primary — explicitly the {@code image} part when
     * present, else the first {@code images} entry — so a created gallery
     * always satisfies the one-primary invariant. A PRESENT-but-invalid file
     * (any of them) refuses the WHOLE create — every file is validated before
     * the insert so a bad file never leaves a half-created listing behind.
     */
    @Transactional
    public ListingResponse create(AuthenticatedUser caller, ListingCreateRequest request,
                                  MultipartFile primaryImage, List<MultipartFile> additionalImages) {
        UUID merchantId = resolveCreateMerchantId(caller, request);
        validateRanges(request.priceCents(), request.stockQty());
        List<MultipartFile> extras = additionalImages == null
                ? List.of()
                : additionalImages.stream().filter(Objects::nonNull).toList();
        if (extras.size() > MAX_ADDITIONAL_IMAGES) {
            throw ApiException.badRequest("too_many_images",
                    "At most 9 additional images are allowed (10 in total with the primary)");
        }
        // Row-volume abuse guard. ARCHIVED rows still count: listings are never
        // physically deleted, so this caps the merchant's total row footprint.
        // The check-then-insert race under concurrency is accepted — the cap is
        // an abuse guard, not an invariant. Applies to the TARGET merchant on
        // SUPER_ADMIN on-behalf creation too.
        if (listingRepository.countByMerchantId(merchantId) >= maxPerMerchant) {
            throw ApiException.conflict("listing_limit_reached", "Merchant listing limit reached");
        }
        // Validate EVERY file before any insert (atomicity). A sent-but-empty
        // part still fails with image_required — only an ABSENT part means
        // "no image".
        List<ValidatedImage> validated = new ArrayList<>();
        if (primaryImage != null) {
            validated.add(validateImage(primaryImage));
        }
        for (MultipartFile extra : extras) {
            validated.add(validateImage(extra));
        }
        Instant now = Instant.now();
        Listing listing = Listing.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .shopId(optionalShopId(caller))
                .title(requiredTitle(request.title()))
                .description(sanitizedOrNull(request.description()))
                .categoryCode(resolveCategoryCode(request.categoryCode()))
                .condition(request.condition() == null ? ItemCondition.NEW : request.condition())
                .city(sanitizedOrNull(request.city()))
                .area(sanitizedOrNull(request.area()))
                .priceCents(request.priceCents())
                .currency(cellCurrency)
                .stockQty(request.stockQty())
                .status(ListingStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build();
        listingRepository.save(listing);
        insertGallery(listing.getId(), validated, now);
        auditService.record(AuditEventType.LISTING_CREATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", merchantId.toString(),
                        "priceCents", listing.getPriceCents(),
                        "stockQty", listing.getStockQty(),
                        "status", listing.getStatus().name(),
                        "categoryCode", listing.getCategoryCode(),
                        "imageCount", validated.size()));
        metrics.listingCreated();
        return assembler.toResponse(listing);
    }

    @Transactional
    public ListingResponse update(AuthenticatedUser caller, UUID listingId, ListingUpdateRequest request) {
        validateRanges(request.priceCents(), request.stockQty());
        Listing listing = managedListing(caller, listingId);
        int previousStock = listing.getStockQty();
        listing.setTitle(requiredTitle(request.title()));
        listing.setDescription(sanitizedOrNull(request.description()));
        listing.setCategoryCode(resolveCategoryCode(request.categoryCode()));
        listing.setCondition(request.condition() == null ? ItemCondition.NEW : request.condition());
        listing.setCity(sanitizedOrNull(request.city()));
        listing.setArea(sanitizedOrNull(request.area()));
        listing.setPriceCents(request.priceCents());
        listing.setStockQty(request.stockQty());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        // Restock-alert foundation: a merchant refilling an out-of-stock
        // listing publishes the in-process event; the AFTER_COMMIT listener
        // (favorite/RestockAlertListener) only fires if this tx commits.
        if (previousStock == 0 && listing.getStockQty() > 0) {
            eventPublisher.publishEvent(new ListingRestocked(listing.getId()));
        }
        auditService.record(AuditEventType.LISTING_UPDATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "priceCents", listing.getPriceCents(),
                        "stockQty", listing.getStockQty()));
        return assembler.toResponse(listing);
    }

    /**
     * Status change with the PUBLISH GATE (owner decision, 2026-08-06): a
     * transition TO ACTIVE requires the gallery to have a primary image —
     * 422 {@code primary_image_required} otherwise. Drafts may stay imageless
     * indefinitely; the guard sits on the transition only, so listings that
     * were already ACTIVE before V3 keep working (and an ACTIVE→ACTIVE no-op
     * is not a transition).
     */
    @Transactional
    public ListingResponse changeStatus(AuthenticatedUser caller, UUID listingId, ListingStatusRequest request) {
        Listing listing = managedListing(caller, listingId);
        ListingStatus from = listing.getStatus();
        if (request.status() == ListingStatus.ACTIVE && from != ListingStatus.ACTIVE
                && !listingImageRepository.existsByListingIdAndPrimaryImageTrue(listing.getId())) {
            throw ApiException.unprocessable("primary_image_required",
                    "A primary image is required before a listing can be published");
        }
        listing.setStatus(request.status());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        auditService.record(AuditEventType.LISTING_STATUS_CHANGED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "from", from.name(),
                        "to", listing.getStatus().name()));
        return assembler.toResponse(listing);
    }

    /**
     * PUT /{id}/image (back-compat V2 contract): REPLACES the primary image
     * in place, or creates it when the gallery has none. Validation is
     * event-service's banner discipline: allow-listed content type AND
     * magic-byte signature (the declared type is attacker-controlled), size
     * capped at {@link #MAX_IMAGE_BYTES}. Owner-or-SUPER_ADMIN via
     * {@link #managedListing}.
     */
    @Transactional
    public ListingResponse uploadImage(AuthenticatedUser caller, UUID listingId, MultipartFile file) {
        Listing listing = managedListing(caller, listingId);
        ValidatedImage validated = validateImage(file);
        Instant now = Instant.now();
        ListingImage primary = listingImageRepository
                .findByListingIdAndPrimaryImageTrue(listing.getId())
                .orElse(null);
        if (primary != null) {
            // In-place replace keeps the imageId (and thus any cached
            // per-image URL semantics identical to the old single-image
            // column replace).
            primary.setImageBytes(validated.bytes());
            primary.setContentType(validated.contentType());
            primary.setCreatedAt(now);
            listingImageRepository.save(primary);
        } else {
            requireGalleryCapacity(listing.getId());
            insertImage(listing.getId(), validated, true, now);
        }
        touch(listing, now);
        auditService.record(AuditEventType.LISTING_IMAGE_UPDATED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "contentType", validated.contentType(),
                        "sizeBytes", validated.bytes().length,
                        "replacedExisting", primary != null));
        return assembler.toResponse(listing);
    }

    /**
     * POST /{id}/images: ADD one image to the gallery. Appended after the
     * current last position; non-primary — EXCEPT into an empty gallery,
     * where the sole image becomes primary so the one-primary-when-any-images
     * invariant (and the publish gate) can't be wedged into an unpublishable
     * state. 409 {@code image_limit_reached} at {@value #MAX_GALLERY_IMAGES}.
     */
    @Transactional
    public ListingResponse addImage(AuthenticatedUser caller, UUID listingId, MultipartFile file) {
        Listing listing = managedListing(caller, listingId);
        ValidatedImage validated = validateImage(file);
        long count = listingImageRepository.countByListingId(listing.getId());
        if (count >= MAX_GALLERY_IMAGES) {
            throw ApiException.conflict("image_limit_reached",
                    "A listing can have at most 10 images");
        }
        Instant now = Instant.now();
        ListingImage saved = insertImage(listing.getId(), validated, count == 0, now);
        touch(listing, now);
        auditService.record(AuditEventType.LISTING_IMAGE_ADDED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "imageId", saved.getId().toString(),
                        "contentType", validated.contentType(),
                        "sizeBytes", validated.bytes().length,
                        "primary", saved.isPrimaryImage()));
        return assembler.toResponse(listing);
    }

    /**
     * DELETE /{id}/images/{imageId}: remove one gallery image (404
     * {@code image_not_found} when the id is not an image OF THIS listing —
     * no cross-listing probing). Deleting the primary promotes the
     * lowest-position survivor, keeping the invariant.
     */
    @Transactional
    public ListingResponse deleteGalleryImage(AuthenticatedUser caller, UUID listingId, UUID imageId) {
        Listing listing = managedListing(caller, listingId);
        ImageMeta meta = listingImageRepository.findMetaByIdAndListingId(imageId, listing.getId())
                .orElseThrow(() -> ApiException.notFound("image_not_found",
                        "No such image for this listing"));
        removeAndPromote(listing.getId(), imageId, meta.isPrimaryImage());
        touch(listing, Instant.now());
        auditService.record(AuditEventType.LISTING_IMAGE_DELETED, caller.uuid(), listing.getId().toString(),
                Map.of("merchantId", listing.getMerchantId().toString(),
                        "imageId", imageId.toString(),
                        "wasPrimary", meta.isPrimaryImage()));
        return assembler.toResponse(listing);
    }

    /**
     * DELETE /{id}/image (back-compat V2 contract): removes the PRIMARY image,
     * promoting the next gallery image (lowest position) if any remain.
     * Deleting when no primary exists is a no-op 200 — the end state is
     * identical. Owner-or-SUPER_ADMIN.
     */
    @Transactional
    public ListingResponse deleteImage(AuthenticatedUser caller, UUID listingId) {
        Listing listing = managedListing(caller, listingId);
        ImageMeta primary = listingImageRepository
                .findMetaByListingIdAndPrimaryImageTrue(listing.getId())
                .orElse(null);
        if (primary != null) {
            removeAndPromote(listing.getId(), primary.getId(), true);
            auditService.record(AuditEventType.LISTING_IMAGE_DELETED, caller.uuid(), listing.getId().toString(),
                    Map.of("merchantId", listing.getMerchantId().toString(),
                            "imageId", primary.getId().toString(),
                            "wasPrimary", true));
        }
        touch(listing, Instant.now());
        return assembler.toResponse(listing);
    }

    /**
     * PUT /{id}/images/{imageId}/primary: atomic primary swap — demote the
     * current primary, mark the named image. Both are ordered bulk statements
     * (demote FIRST), so the partial unique index never sees two primaries;
     * it remains the DB backstop if this ordering ever regresses. Setting the
     * current primary again is a no-op 200.
     */
    @Transactional
    public ListingResponse setPrimaryImage(AuthenticatedUser caller, UUID listingId, UUID imageId) {
        Listing listing = managedListing(caller, listingId);
        ImageMeta meta = listingImageRepository.findMetaByIdAndListingId(imageId, listing.getId())
                .orElseThrow(() -> ApiException.notFound("image_not_found",
                        "No such image for this listing"));
        if (!meta.isPrimaryImage()) {
            listingImageRepository.demotePrimary(listing.getId());
            listingImageRepository.markPrimary(imageId);
            auditService.record(AuditEventType.LISTING_IMAGE_PRIMARY_CHANGED, caller.uuid(),
                    listing.getId().toString(),
                    Map.of("merchantId", listing.getMerchantId().toString(),
                            "imageId", imageId.toString()));
        }
        touch(listing, Instant.now());
        return assembler.toResponse(listing);
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
        return ListingPageResponse.from(assembler.toResponsePage(result));
    }

    // ------------------------------------------------------------------
    // Gallery internals
    // ------------------------------------------------------------------

    /** Inserts the create-time gallery: first file primary, positions 0..n. */
    private void insertGallery(UUID listingId, List<ValidatedImage> images, Instant now) {
        for (int i = 0; i < images.size(); i++) {
            insertImageAt(listingId, images.get(i), i == 0, i, now);
        }
    }

    /** Appends one image after the current highest position. */
    private ListingImage insertImage(UUID listingId, ValidatedImage image, boolean primary, Instant now) {
        int position = listingImageRepository.maxPosition(listingId) + 1;
        return insertImageAt(listingId, image, primary, position, now);
    }

    private ListingImage insertImageAt(UUID listingId, ValidatedImage image, boolean primary,
                                       int position, Instant now) {
        ListingImage row = ListingImage.builder()
                .id(UUID.randomUUID())
                .listingId(listingId)
                .imageBytes(image.bytes())
                .contentType(image.contentType())
                .primaryImage(primary)
                .position(position)
                .createdAt(now)
                .build();
        listingImageRepository.save(row);
        return row;
    }

    /**
     * Deletes one image row and, when it was the primary, promotes the
     * lowest-position survivor. Statement order matters for the partial
     * unique index: the bulk DELETE executes immediately, so the follow-up
     * markPrimary can never collide with the removed row.
     */
    private void removeAndPromote(UUID listingId, UUID imageId, boolean wasPrimary) {
        listingImageRepository.deleteImageRow(imageId);
        if (wasPrimary) {
            listingImageRepository.findFirstByListingIdOrderByPositionAscCreatedAtAsc(listingId)
                    .ifPresent(next -> listingImageRepository.markPrimary(next.getId()));
        }
    }

    private void requireGalleryCapacity(UUID listingId) {
        if (listingImageRepository.countByListingId(listingId) >= MAX_GALLERY_IMAGES) {
            throw ApiException.conflict("image_limit_reached",
                    "A listing can have at most 10 images");
        }
    }

    private void touch(Listing listing, Instant now) {
        listing.setUpdatedAt(now);
        listingRepository.save(listing);
    }

    /**
     * Normalizes and validates the taxonomy code: absent/blank defaults to
     * {@link #DEFAULT_CATEGORY_CODE}; anything else must exist in the seeded
     * {@code category} table (parent or child — both are storable) or the
     * write is refused with 400 {@code unknown_category}.
     */
    private String resolveCategoryCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CATEGORY_CODE;
        }
        String code = raw.trim().toLowerCase(Locale.ROOT);
        if (!categoryRepository.existsById(code)) {
            throw ApiException.badRequest("unknown_category",
                    "categoryCode is not part of the marketplace taxonomy");
        }
        return code;
    }

    // ------------------------------------------------------------------
    // Scope helpers
    // ------------------------------------------------------------------

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

    /** Validated upload: bytes + normalized content type, ready to store. */
    record ValidatedImage(byte[] bytes, String contentType) {}

    private static ValidatedImage validateImage(MultipartFile file) {
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
        return new ValidatedImage(bytes, contentType.toLowerCase(Locale.ROOT));
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
