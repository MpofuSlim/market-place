package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.UUID;

/**
 * Public catalog reads — permitAll (GET only) in {@code SecurityConfig}, no
 * token required. Serves ACTIVE listings exclusively; the example bodies show
 * the same record the Merchant Listings examples created and activated.
 */
@Tag(name = "Public Catalog",
        description = "Unauthenticated browse/read of ACTIVE listings. All prices are minor units "
                + "(cents) in the cell currency; timestamps are UTC instants.")
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
                    "category": "electronics",
                    "priceCents": 2399,
                    "currency": "USD",
                    "stockQty": 150,
                    "status": "ACTIVE",
                    "createdAt": "2026-08-05T09:15:00Z",
                    "updatedAt": "2026-08-05T09:25:00Z",
                    "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image"
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
                "category": "electronics",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "ACTIVE",
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:25:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image"
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

    @Operation(summary = "Browse the catalog",
            description = "ACTIVE listings only, newest first. Optional case-insensitive title "
                    + "'contains' filter (q) and exact category filter. Page size is clamped to 50 "
                    + "(never an error).")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of ACTIVE listings "
                    + "(an out-of-range page simply returns empty items)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "browse", value = EXAMPLE_BROWSE_200)))
    })
    @GetMapping
    public ApiResult<ListingPageResponse> browse(
            @Parameter(description = "Case-insensitive title 'contains' filter",
                    example = "speaker")
            @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Exact category filter", example = "electronics")
            @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "Zero-based page index",
                    schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(value = "page", defaultValue = "0") String page,
            @Parameter(description = "Page size (clamped to 50)",
                    schema = @Schema(type = "integer", defaultValue = "20"))
            @RequestParam(value = "size", defaultValue = "20") String size) {
        return ApiResult.ok(catalogService.browse(q, category,
                intParam(page, 0), intParam(size, 20)));
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

    @Operation(summary = "Get a listing's image",
            description = "Returns the raw bytes of the listing image with its original Content-Type. "
                    + "Served for ANY listing status (a DRAFT owner needs the preview; ids are "
                    + "unguessable UUIDs) — 404 only when the listing is unknown or has no image, "
                    + "indistinguishably.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image bytes (image/jpeg, image/png or "
                    + "image/webp; X-Content-Type-Options: nosniff; cacheable publicly for 1h)",
                    content = @Content(mediaType = "image/png",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "Malformed id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400))),
            @ApiResponse(responseCode = "404", description = "Unknown listing id, or no image uploaded",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-not-found",
                                    value = EXAMPLE_IMAGE_NOT_FOUND_404)))
    })
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        CatalogService.ListingImage image = catalogService.getImage(parseListingId(id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                // OWASP A03: stop the browser MIME-sniffing the stored bytes into
                // an executable type (e.g. HTML/JS) regardless of the served
                // Content-Type — defence-in-depth alongside upload magic-byte checks.
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(image.bytes());
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

    private static int intParam(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
