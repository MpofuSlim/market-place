package com.innbucks.marketplaceservice.review.dto;

import com.innbucks.marketplaceservice.review.ListingReview;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The review as its AUTHOR (or an admin write path) sees it — carries the raw
 * ids. The public catalog read uses {@link PublicReviewResponse} instead,
 * which anonymizes the reviewer.
 */
@Schema(description = "A review, as seen by its author")
public record ReviewResponse(

        @Schema(description = "Review id", example = "3a9d5c7e-1b2f-4a8c-9d6e-5f4a3b2c1d0e")
        UUID id,

        @Schema(description = "Reviewed listing", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
        UUID listingId,

        @Schema(description = "The PAID order that qualified this reviewer",
                example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID orderId,

        @Schema(description = "Star rating 1..5", example = "5")
        int rating,

        @Schema(description = "Sanitized comment; null when none was given",
                example = "Great speaker, battery really does last all day.", nullable = true)
        String comment,

        @Schema(description = "UTC instant", example = "2026-08-06T10:15:00Z")
        Instant createdAt,

        @Schema(description = "UTC instant", example = "2026-08-06T10:15:00Z")
        Instant updatedAt
) {

    public static ReviewResponse from(ListingReview review) {
        return new ReviewResponse(review.getId(), review.getListingId(), review.getOrderId(),
                review.getRating(), review.getComment(), review.getCreatedAt(),
                review.getUpdatedAt());
    }
}
