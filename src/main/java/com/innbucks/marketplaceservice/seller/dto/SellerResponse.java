package com.innbucks.marketplaceservice.seller.dto;

import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing view of a seller's trust record. */
@Schema(description = "A seller's trust record as the moderation queue sees it")
public record SellerResponse(

        @Schema(description = "Fleet merchant id — the seller's identity and this record's key",
                example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        SellerStatus status,

        @Schema(description = "Trading name shown on the buyer-facing badge; null until an admin sets one",
                example = "Rudo Traders", nullable = true)
        String displayName,

        @Schema(description = "Whether a buyer sees the verified badge. True for APPROVED only.",
                example = "true")
        boolean verified,

        @Schema(description = "Whether this seller may publish a listing (move it to ACTIVE). "
                + "False for REJECTED and SUSPENDED.", example = "true")
        boolean canPublish,

        @Schema(description = "uuid of the SUPER_ADMIN who last decided", nullable = true)
        UUID decidedBy,

        @Schema(description = "The admin's note. Always set on REJECTED/SUSPENDED.", nullable = true,
                example = "Business verification outstanding.")
        String decisionNote,

        @Schema(description = "When this seller first appeared — their first listing for a "
                + "backfilled record. Also the badge's 'selling since'.",
                example = "2026-04-01T09:15:00Z")
        Instant createdAt,

        @Schema(nullable = true, example = "2026-09-09T10:15:00Z")
        Instant decidedAt
) {
    public static SellerResponse from(MarketplaceSeller s) {
        return new SellerResponse(
                s.getMerchantId(),
                s.getStatus(),
                s.getDisplayName(),
                s.getStatus().isVerified(),
                s.getStatus().canPublish(),
                s.getDecidedBy(),
                s.getDecisionNote(),
                s.getCreatedAt(),
                s.getDecidedAt());
    }
}
