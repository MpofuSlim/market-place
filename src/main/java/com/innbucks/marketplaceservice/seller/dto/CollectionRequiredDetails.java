package com.innbucks.marketplaceservice.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * The {@code data} of a 409 {@code collection_required}: the seller's items
 * on sale that can only be collected, so the portal can link straight to them
 * rather than making the seller hunt.
 *
 * <p>A record, not a map, so the keys render in a fixed order.
 */
@Schema(description = "The items on sale that stop collection being turned off")
public record CollectionRequiredDetails(

        @Schema(description = "ACTIVE listings with no delivery town, oldest first - at most 20")
        List<StrandedListing> listings,

        @Schema(description = "True when there are more such listings than the ones named here",
                example = "false")
        boolean truncated) {

    /** One listing that can only be collected. */
    public record StrandedListing(
            @Schema(example = "9c2e8a4d-6b1f-4e3a-8d5c-2f7b9a1e4c63") UUID id,
            @Schema(example = "Wireless Earbuds") String title) {
    }
}
