package com.innbucks.marketplaceservice.review;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.review.dto.MerchantRatingResponse;
import com.innbucks.marketplaceservice.review.dto.ReviewPageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public (unauthenticated) review reads — under {@code /marketplace/catalog},
 * so SecurityConfig's GET-scoped permitAll covers them with no new matcher.
 * Reviewers are anonymized ({@code "Verified buyer"} + a stable derived
 * handle); raw buyer uuids never leave the service on this surface.
 */
@Tag(name = "Public Catalog")
@RestController
@RequestMapping("/marketplace/catalog")
@RequiredArgsConstructor
public class PublicReviewController {

    private final ReviewService reviewService;

    private static final String EXAMPLE_REVIEWS_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": "3a9d5c7e-1b2f-4a8c-9d6e-5f4a3b2c1d0e",
                    "rating": 5,
                    "comment": "Great speaker, battery really does last all day.",
                    "createdAt": "2026-08-06T10:15:00Z",
                    "reviewerName": "Verified buyer",
                    "reviewerHandle": "Buyer-4f9a"
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String EXAMPLE_MERCHANT_RATING_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "ratingAvg": 5.0,
                "reviewCount": 1
              }
            }""";

    @Operation(summary = "List a listing's reviews",
            description = "Verified-purchase reviews, newest first, reviewer anonymized (constant "
                    + "\"Verified buyer\" plus a stable Buyer-XXXX handle — repeat reviewers are "
                    + "recognizable, identity is not). Served for ANY listing status (a delisted "
                    + "product's reviews stay readable); 404 only for an unknown listing id. Page "
                    + "size is clamped to 50.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of reviews, newest first",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "reviews", value = EXAMPLE_REVIEWS_200))),
            @ApiResponse(responseCode = "400", description = "Malformed listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_listing_id","message":"Listing id must be a UUID"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Unknown listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"listing_not_found","message":"Listing not found"}
                                    """)))
    })
    @GetMapping("/{id}/reviews")
    public ApiResult<ReviewPageResponse> reviews(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Zero-based page index",
                    schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(value = "page", defaultValue = "0") String page,
            @Parameter(description = "Page size (clamped to 50)",
                    schema = @Schema(type = "integer", defaultValue = "20"))
            @RequestParam(value = "size", defaultValue = "20") String size) {
        return ApiResult.ok(reviewService.listForListing(parseListingId(id),
                intParam(page, 0), intParam(size, 20)));
    }

    @Operation(summary = "Get a merchant's aggregate rating",
            description = "Average verified-purchase rating and review count across EVERY listing "
                    + "the merchant owns. Never 404s: an unknown or not-yet-reviewed merchant "
                    + "returns {ratingAvg: null, reviewCount: 0} — \"unrated\" is distinguishable "
                    + "from \"rated badly\".")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The merchant's aggregate rating",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "merchant-rating",
                                    value = EXAMPLE_MERCHANT_RATING_200))),
            @ApiResponse(responseCode = "400", description = "Malformed merchant id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_merchant_id","message":"Merchant id must be a UUID"}
                                    """)))
    })
    @GetMapping("/merchants/{merchantId}/rating")
    public ApiResult<MerchantRatingResponse> merchantRating(
            @Parameter(description = "Merchant id", example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("merchantId") String merchantId) {
        return ApiResult.ok(reviewService.merchantRating(parseMerchantId(merchantId)));
    }

    /** Manual parse — public surface, garbage input must 400, never 500 (no
     *  MethodArgumentTypeMismatch mapping in GlobalExceptionHandler). */
    private static UUID parseListingId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_listing_id", "Listing id must be a UUID");
        }
    }

    private static UUID parseMerchantId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_merchant_id", "Merchant id must be a UUID");
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
