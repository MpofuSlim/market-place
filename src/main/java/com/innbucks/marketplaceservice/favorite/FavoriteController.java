package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Buyer favorites — CUSTOMER-only. PUT/DELETE are idempotent toggles (the FE
 * heart button can retry blindly); the GET returns full listing summaries via
 * the shared assembler so images/category/rating/status all come along.
 */
@Tag(name = "Favorites",
        description = "A buyer's saved listings. Adding and removing are idempotent (repeat = 200 "
                + "no-op). Any listing status can be favorited and stays listed — rows carry the "
                + "listing's CURRENT status so the app can show \"no longer available\". Favorite "
                + "counts are deliberately not exposed anywhere.")
@RestController
@RequestMapping("/marketplace/favorites")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class FavoriteController {

    private final FavoriteService favoriteService;

    private static final String EXAMPLE_FAVORITES_200 = """
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

    private static final String EXAMPLE_401 = """
            {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}""";

    private static final String EXAMPLE_ROLE_403 = """
            {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}""";

    private static final String EXAMPLE_INVALID_ID_400 = """
            {"code":"invalid_listing_id","message":"Listing id must be a UUID"}""";

    @PutMapping("/{listingId}")
    @Operation(summary = "Favorite a listing (idempotent)",
            description = "Adds the listing to the caller's favorites. Repeating the call is a 200 "
                    + "no-op — the original favorited-at ordering is preserved. The listing must "
                    + "exist but may be in ANY status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Favorited (or already was)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"OK","message":"Favorited"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Malformed listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_INVALID_ID_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ROLE_403))),
            @ApiResponse(responseCode = "404", description = "Unknown listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"listing_not_found","message":"Listing not found"}
                                    """)))
    })
    public ApiResult<Void> add(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("listingId") String listingId) {
        favoriteService.add(CurrentUser.get(), parseListingId(listingId));
        return ApiResult.ok("Favorited", null);
    }

    @DeleteMapping("/{listingId}")
    @Operation(summary = "Unfavorite a listing (idempotent)",
            description = "Removes the listing from the caller's favorites. Removing one that was "
                    + "never favorited (or no longer exists) is the same 200 no-op — the end state "
                    + "is identical.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Not favorited any more (whether or not it was)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"OK","message":"Unfavorited"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Malformed listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_INVALID_ID_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ROLE_403)))
    })
    public ApiResult<Void> remove(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("listingId") String listingId) {
        favoriteService.remove(CurrentUser.get(), parseListingId(listingId));
        return ApiResult.ok("Unfavorited", null);
    }

    @GetMapping
    @Operation(summary = "List my favorites",
            description = "The caller's favorited listings as full listing summaries (gallery URLs, "
                    + "category, rating aggregates), newest-FAVORITED first. Rows include each "
                    + "listing's CURRENT status — DRAFT/INACTIVE/ARCHIVED favorites stay listed so "
                    + "the app can render \"no longer available\". Page size is clamped to 50.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of favorited listings",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "favorites",
                                    value = EXAMPLE_FAVORITES_200))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ROLE_403)))
    })
    public ApiResult<ListingPageResponse> listMine(
            @Parameter(description = "Zero-based page index",
                    schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(value = "page", defaultValue = "0") String page,
            @Parameter(description = "Page size (clamped to 50)",
                    schema = @Schema(type = "integer", defaultValue = "20"))
            @RequestParam(value = "size", defaultValue = "20") String size) {
        return ApiResult.ok(favoriteService.listMine(CurrentUser.get(),
                intParam(page, 0), intParam(size, 20)));
    }

    /** Manual parse — GlobalExceptionHandler has no MethodArgumentTypeMismatch
     *  mapping, so a typed UUID @PathVariable would 500 on garbage. */
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
