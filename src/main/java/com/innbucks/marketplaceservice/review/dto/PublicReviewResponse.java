package com.innbucks.marketplaceservice.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A review on the PUBLIC catalog surface. The reviewer is anonymized: a raw
 * buyer uuid would be meaningless to clients AND a cross-service correlation
 * handle, so instead every row carries the constant {@code reviewerName}
 * ("Verified buyer" — every review here IS purchase-verified by construction)
 * plus a stable derived handle ({@code Buyer-<4 hex of sha256(buyer_uuid)>}):
 * no PII leaks, but a repeat reviewer is recognizable across a listing's
 * reviews.
 */
@Schema(description = "A verified-purchase review as shown on the public catalog")
public record PublicReviewResponse(

        @Schema(description = "Review id", example = "3a9d5c7e-1b2f-4a8c-9d6e-5f4a3b2c1d0e")
        UUID id,

        @Schema(description = "Star rating 1..5", example = "5")
        int rating,

        @Schema(description = "Sanitized comment; null when none was given",
                example = "Great speaker, battery really does last all day.", nullable = true)
        String comment,

        @Schema(description = "UTC instant", example = "2026-08-06T10:15:00Z")
        Instant createdAt,

        @Schema(description = "Constant display name — every review on this surface is "
                + "purchase-verified", example = "Verified buyer")
        String reviewerName,

        @Schema(description = "Stable anonymized reviewer handle (Buyer- + 4 hex of "
                + "sha256(buyer uuid)) — repeat reviewers are recognizable, identity is not",
                example = "Buyer-4f9a")
        String reviewerHandle
) {
}
