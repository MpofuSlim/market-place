package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.MerchantProfileResponse;
import com.innbucks.marketplaceservice.catalog.util.QueryParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Public catalog reads — permitAll (GET only) in {@code SecurityConfig}, no
 * token required. Serves ACTIVE listings exclusively; the example bodies show
 * the same record the Merchant Listings examples created and activated.
 */
@Tag(name = "Public Catalog",
        description = "Unauthenticated browse/read of ACTIVE listings. All prices are minor units "
                + "(cents) in the cell currency; timestamps are UTC instants. Listings carry an "
                + "image gallery: imageUrl serves the primary, imageUrls every image primary-first.")
@RestController
@RequestMapping("/marketplace/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    private static final String EXAMPLE_BROWSE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "title": "Wireless Bluetooth Speaker",
                    "description": "Portable speaker with 12h battery life.",
                    "categoryCode": "tv-audio",
                    "categoryName": "TV & Audio",
                    "condition": "NEW",
                    "city": "Harare",
                    "area": "Avondale",
                    "priceCents": 2399,
                    "currency": "USD",
                    "stockQty": 150,
                    "status": "ACTIVE",
                    "ratingAvg": 5.0,
                    "reviewCount": 1,
                    "createdAt": "2026-08-05T09:15:00Z",
                    "updatedAt": "2026-08-05T09:26:00Z",
                    "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                    "imageUrls": [
                      "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"
                    ]
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String EXAMPLE_GET_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "ACTIVE",
                "ratingAvg": 5.0,
                "reviewCount": 1,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:26:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                "imageUrls": [
                  "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"
                ]
              }
            }""";

    private static final String EXAMPLE_NOT_FOUND_404 = """
            {
              "code": "listing_not_found",
              "message": "Listing not found"
            }""";

    private static final String EXAMPLE_IMAGE_NOT_FOUND_404 = """
            {
              "code": "image_not_found",
              "message": "No image has been uploaded for this listing"
            }""";

    private static final String EXAMPLE_INVALID_ID_400 = """
            {
              "code": "invalid_listing_id",
              "message": "Listing id must be a UUID"
            }""";

    private static final String EXAMPLE_INVALID_IMAGE_ID_400 = """
            {
              "code": "invalid_image_id",
              "message": "Image id must be a UUID"
            }""";

    private static final String EXAMPLE_INVALID_CONDITION_400 = """
            {
              "code": "invalid_condition",
              "message": "condition must be one of NEW, USED_LIKE_NEW, USED_GOOD, USED_FAIR"
            }""";

    private static final String EXAMPLE_INVALID_SORT_400 = """
            {
              "code": "invalid_sort",
              "message": "sort must be one of newest, price_asc, price_desc"
            }""";

    private static final String EXAMPLE_INVALID_PRICE_RANGE_400 = """
            {
              "code": "invalid_price_range",
              "message": "minPriceCents (5000) must not exceed maxPriceCents (1000)"
            }""";

    private static final String EXAMPLE_UNKNOWN_PARAMETER_400 = """
            {
              "code": "unknown_parameter",
              "message": "Unknown query parameter 'minPrice'. Supported parameters: category, city, \
            condition, inStock, maxPriceCents, merchantId, minPriceCents, page, q, size, sort"
            }""";

    /**
     * Everything browse understands. A parameter outside this set is a 400
     * rather than a silently dropped filter — see
     * {@link com.innbucks.marketplaceservice.catalog.util.QueryParams}. Keep it
     * in lock-step with the {@code @RequestParam} names below; the Swagger
     * example above prints the same set.
     */
    static final Set<String> BROWSE_PARAMS = Set.of(
            "q", "category", "condition", "city", "merchantId",
            "minPriceCents", "maxPriceCents", "inStock", "sort", "page", "size");

    @Operation(summary = "Browse the catalog",
            description = "ACTIVE listings only. Optional filters, all combinable: case-insensitive "
                    + "title 'contains' (q); category by taxonomy code — a PARENT code (e.g. "
                    + "electronics) also matches listings in its children (e.g. tv-audio); condition "
                    + "(NEW/USED_LIKE_NEW/USED_GOOD/USED_FAIR); city (exact, case-insensitive — "
                    + "geo/radius search is future work); merchantId ('more from this seller'); an "
                    + "inclusive minPriceCents/maxPriceCents window in MINOR units; and inStock=true "
                    + "to hide listings sitting at zero stock. Ordering is `sort` — newest (default), "
                    + "price_asc or price_desc — each with a stable tiebreaker so paging never "
                    + "repeats or skips a row. Page size is clamped to 50 (never an error). "
                    + "**An unrecognised query parameter is refused with 400 `unknown_parameter`** "
                    + "rather than ignored, so a filter can never be silently dropped.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of ACTIVE listings "
                    + "(an out-of-range page simply returns empty items)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "browse", value = EXAMPLE_BROWSE_200))),
            @ApiResponse(responseCode = "400", description = "condition outside the enum, an "
                    + "unrecognised sort, an inverted/negative price window, a malformed merchantId, "
                    + "or a query parameter this endpoint does not support",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "invalid-condition",
                                    value = EXAMPLE_INVALID_CONDITION_400),
                            @ExampleObject(name = "invalid-sort", value = EXAMPLE_INVALID_SORT_400),
                            @ExampleObject(name = "invalid-price-range",
                                    value = EXAMPLE_INVALID_PRICE_RANGE_400),
                            @ExampleObject(name = "unknown-parameter",
                                    value = EXAMPLE_UNKNOWN_PARAMETER_400)}))
    })
    @GetMapping
    public ApiResult<ListingPageResponse> browse(
            @Parameter(description = "Case-insensitive title 'contains' filter",
                    example = "speaker")
            @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Taxonomy code filter (see GET /marketplace/categories). "
                    + "A parent code expands to itself + its children.", example = "electronics")
            @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "Condition filter", example = "NEW",
                    schema = @Schema(implementation = ItemCondition.class))
            @RequestParam(value = "condition", required = false) String condition,
            @Parameter(description = "Exact city filter (case-insensitive)", example = "Harare")
            @RequestParam(value = "city", required = false) String city,
            @Parameter(description = "Seller filter — the merchantId carried on every listing. "
                    + "Powers 'more from this seller'.",
                    example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestParam(value = "merchantId", required = false) String merchantId,
            @Parameter(description = "Inclusive lower price bound, MINOR units (cents)",
                    example = "1000", schema = @Schema(type = "integer", format = "int64"))
            @RequestParam(value = "minPriceCents", required = false) String minPriceCents,
            @Parameter(description = "Inclusive upper price bound, MINOR units (cents)",
                    example = "5000", schema = @Schema(type = "integer", format = "int64"))
            @RequestParam(value = "maxPriceCents", required = false) String maxPriceCents,
            @Parameter(description = "true keeps only listings with stock left. An ACTIVE listing "
                    + "may sit at stockQty 0.", example = "true",
                    schema = @Schema(type = "boolean"))
            @RequestParam(value = "inStock", required = false) String inStock,
            @Parameter(description = "Ordering: newest (default), price_asc or price_desc",
                    example = "price_asc", schema = @Schema(implementation = ListingSort.class))
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "Zero-based page index",
                    schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(value = "page", defaultValue = "0") String page,
            @Parameter(description = "Page size (clamped to 50)",
                    schema = @Schema(type = "integer", defaultValue = "20"))
            @RequestParam(value = "size", defaultValue = "20") String size,
            HttpServletRequest httpRequest) {
        QueryParams.rejectUnknown(httpRequest, BROWSE_PARAMS);
        CatalogService.BrowseQuery query = new CatalogService.BrowseQuery(
                q, category, condition, city, parseMerchantId(merchantId),
                longParam("minPriceCents", minPriceCents), longParam("maxPriceCents", maxPriceCents),
                booleanParam("inStock", inStock), ListingSort.parse(sort),
                intParam(page, 0), intParam(size, 20));
        return ApiResult.ok(catalogService.browse(query));
    }

    private static final String EXAMPLE_MERCHANT_PROFILE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "displayName": "Rudo Traders",
                "verified": true,
                "since": "2026-04-01T09:15:00Z",
                "ratingAvg": 5.0,
                "reviewCount": 1,
                "activeListingCount": 1
              }
            }""";

    private static final String EXAMPLE_INVALID_MERCHANT_ID_400 = """
            {
              "code": "invalid_merchant_id",
              "message": "merchantId must be a UUID"
            }""";

    @Operation(summary = "Get one seller's public profile",
            description = "Badge, aggregate rating and live listing count for one merchant — the "
                    + "seller header, in a single call. Pair it with "
                    + "`GET /marketplace/catalog?merchantId=<id>` for 'more from this seller'. "
                    + "**Never 404s:** an unknown merchant answers with a nameless, unverified "
                    + "profile and zeroes, so this endpoint is not an oracle for which merchant ids "
                    + "exist. A null ratingAvg means unrated — render it as such, never as 0. "
                    + "Logo, response time and return policy are deliberately absent: the platform "
                    + "stores none of them and will not invent them.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The seller profile (zeroed for an "
                    + "unknown merchant)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "profile",
                                    value = EXAMPLE_MERCHANT_PROFILE_200))),
            @ApiResponse(responseCode = "400", description = "Malformed merchant id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "invalid-merchant-id",
                                    value = EXAMPLE_INVALID_MERCHANT_ID_400)))
    })
    @GetMapping("/merchants/{merchantId}")
    public ApiResult<MerchantProfileResponse> merchantProfile(
            @Parameter(description = "Fleet merchant id — the merchantId on any of their listings",
                    example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("merchantId") String merchantId) {
        UUID id = parseMerchantId(merchantId);
        if (id == null) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId must be a UUID");
        }
        return ApiResult.ok(catalogService.merchantProfile(id));
    }

    @Operation(summary = "Get one listing",
            description = "Returns the listing only while it is ACTIVE — DRAFT/INACTIVE/ARCHIVED "
                    + "listings are indistinguishable from nonexistent ones (404).")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The ACTIVE listing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "listing", value = EXAMPLE_GET_200))),
            @ApiResponse(responseCode = "400", description = "Malformed id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400))),
            @ApiResponse(responseCode = "404", description = "Unknown id, or the listing is not ACTIVE",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "not-found", value = EXAMPLE_NOT_FOUND_404)))
    })
    @GetMapping("/{id}")
    public ApiResult<ListingResponse> getById(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return ApiResult.ok(catalogService.getById(parseListingId(id)));
    }

    @Operation(summary = "Get a listing's PRIMARY image",
            description = "Returns the raw bytes of the gallery's PRIMARY image with its original "
                    + "Content-Type (the unchanged pre-gallery contract). Served for ANY listing "
                    + "status (a DRAFT owner needs the preview; ids are unguessable UUIDs) — 404 "
                    + "only when the listing is unknown or has no primary image, indistinguishably.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image bytes (image/jpeg, image/png or "
                    + "image/webp; X-Content-Type-Options: nosniff; cacheable publicly for 1h)",
                    content = @Content(mediaType = "image/png",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "Malformed id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400))),
            @ApiResponse(responseCode = "404", description = "Unknown listing id, or no primary image",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-not-found",
                                    value = EXAMPLE_IMAGE_NOT_FOUND_404)))
    })
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Optional downscale width. One of 120, 240, 480, 960 — "
                    + "anything else is a 400. Omit for the original. Never upscales, and "
                    + "WebP is served unresized (no JDK decoder).", example = "240")
            @RequestParam(value = "w", required = false) Integer w) {
        return imageResponse(catalogService.getImage(parseListingId(id)), w);
    }

    @Operation(summary = "Get one gallery image",
            description = "Returns the raw bytes of ONE image of the listing's gallery (the URLs in "
                    + "imageUrls point here). The (listingId, imageId) pair must match — an imageId "
                    + "can never be fetched through another listing's URL. Same status-independent "
                    + "serving and indistinguishable 404 as the primary-image endpoint.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image bytes (image/jpeg, image/png or "
                    + "image/webp; X-Content-Type-Options: nosniff; cacheable publicly for 1h)",
                    content = @Content(mediaType = "image/png",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "Malformed listing or image id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "invalid-image-id", value = EXAMPLE_INVALID_IMAGE_ID_400)})),
            @ApiResponse(responseCode = "404", description = "Unknown listing id, or the imageId is "
                    + "not an image of this listing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-not-found",
                                    value = EXAMPLE_IMAGE_NOT_FOUND_404)))
    })
    @GetMapping("/{id}/images/{imageId}")
    public ResponseEntity<byte[]> getImageById(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Gallery image id (from imageUrls)",
                    example = "5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("imageId") String imageId,
            @Parameter(description = "Optional downscale width — see GET /{id}/image.", example = "240")
            @RequestParam(value = "w", required = false) Integer w) {
        return imageResponse(
                catalogService.getImageById(parseListingId(id), parseImageId(imageId)), w);
    }

    private static ResponseEntity<byte[]> imageResponse(CatalogService.ListingImageView image, Integer width) {
        ImageResizer.Resized out = ImageResizer.resize(image.bytes(), image.contentType(), width);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(out.contentType()))
                // Tells a client whether it actually got a smaller copy. A WebP
                // (no JDK decoder) or an already-narrow image comes back at full
                // size, and silently doing so would look like the parameter was
                // ignored.
                .header("X-Image-Resized", Boolean.toString(out.resized()))
                // OWASP A03: stop the browser MIME-sniffing the stored bytes into
                // an executable type (e.g. HTML/JS) regardless of the served
                // Content-Type — defence-in-depth alongside upload magic-byte checks.
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(out.bytes());
    }

    /** Manual parse: GlobalExceptionHandler has no MethodArgumentTypeMismatch
     *  mapping, so a typed UUID @PathVariable would 500 on garbage input —
     *  unacceptable on a public, unauthenticated surface. */
    private static UUID parseListingId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_listing_id", "Listing id must be a UUID");
        }
    }

    private static UUID parseImageId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_image_id", "Image id must be a UUID");
        }
    }

    /**
     * Pagination is FORGIVING — garbage falls back to the default, the
     * long-standing contract on this surface and harmless because a wrong page
     * index only ever shows the wrong slice of the same result set.
     */
    private static int intParam(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Filters are NOT forgiving. Falling back to "no filter" on an unparseable
     * bound is the same failure the unknown-parameter refusal exists to
     * prevent: the client believes it capped the price, the response is a
     * confident 200 listing goods outside the budget, and nothing says the
     * bound was dropped. Refuse instead, naming the parameter.
     */
    private static Long longParam(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("invalid_price",
                    name + " must be a whole number of cents");
        }
    }

    /** Same discipline as {@link #longParam}: only the two literals, never a
     *  {@code Boolean.parseBoolean} that reads every typo as {@code false}. */
    private static Boolean booleanParam(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        throw ApiException.badRequest("invalid_boolean", name + " must be true or false");
    }

    private static UUID parseMerchantId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId must be a UUID");
        }
    }
}
