package com.innbucks.marketplaceservice.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Merchant-level rating aggregate across every review of every listing the
 * merchant owns. Computed from the review table (source of truth), not the
 * per-listing denormalized columns.
 */
@Schema(description = "A merchant's aggregate verified-purchase rating")
public record MerchantRatingResponse(

        @Schema(description = "Merchant id", example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "Average rating across all the merchant's listings, one decimal; "
                + "null when the merchant has no reviews yet", example = "5.0", nullable = true)
        Double ratingAvg,

        @Schema(description = "Total reviews across all the merchant's listings", example = "1")
        long reviewCount
) {
}
