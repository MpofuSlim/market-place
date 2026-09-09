package com.innbucks.marketplaceservice.catalog.dto;

import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The buyer-facing seller badge on a listing (V8).
 *
 * <p><b>Why it exists.</b> A shopper previously had nothing to distinguish a
 * long-standing merchant from one that signed up an hour ago — the payload
 * carried a bare {@code merchantId} UUID and nothing else.
 *
 * <p>{@code verified} is true for an APPROVED seller ONLY. It is a claim the
 * platform makes on its own behalf, so it is never inferred from the mere
 * existence of a merchant: a seller with no trust record, or one still PENDING,
 * is simply not verified.
 */
@Schema(description = "Trust information about the seller of a listing")
public record SellerBadge(

        @Schema(description = "Fleet merchant id — same value as the listing's merchantId",
                example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "Trading name, when the platform has recorded one. Null otherwise — "
                + "render the listing without a seller name rather than inventing one.",
                example = "Rudo Traders", nullable = true)
        String displayName,

        @Schema(description = "Whether the platform has vetted this seller. APPROVED only.",
                example = "true")
        boolean verified,

        @Schema(description = "When this seller first appeared on the marketplace — their first "
                + "listing for a pre-V8 merchant. Null when there is no trust record yet.",
                example = "2026-04-01T09:15:00Z", nullable = true)
        Instant since
) {
    /** The badge for a merchant with no trust record yet: identified, not vetted. */
    public static SellerBadge unknown(UUID merchantId) {
        return new SellerBadge(merchantId, null, false, null);
    }

    public static SellerBadge from(MarketplaceSeller seller) {
        return new SellerBadge(
                seller.getMerchantId(),
                seller.getDisplayName(),
                seller.getStatus().isVerified(),
                seller.getCreatedAt());
    }
}
