package com.innbucks.marketplaceservice.seller.dto;

import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A seller's collection setting as the seller and the operator read it (V20).
 *
 * <p>{@code updatedAt} is deliberately always present (null when never
 * changed) rather than dropped: "never changed" is itself the answer to "who
 * turned this off, and when?".
 */
@Schema(description = "Whether buyers may collect from this seller")
public record CollectionSettingResponse(

        @Schema(description = "true = buyers may collect; false = delivery only. True for every "
                + "seller who has never changed it.", example = "false")
        boolean collectionEnabled,

        @Schema(description = "When the setting was last changed, by the seller or an operator; "
                + "null while it has never been changed.", example = "2026-09-29T08:10:00Z",
                nullable = true)
        Instant updatedAt) {

    /** A seller with no record collects, and has never changed it. */
    public static CollectionSettingResponse of(MarketplaceSeller sellerOrNull) {
        return new CollectionSettingResponse(
                MarketplaceSeller.collects(sellerOrNull),
                sellerOrNull == null ? null : sellerOrNull.getCollectionUpdatedAt());
    }
}
