package com.innbucks.marketplaceservice.pickup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The buyer's choice of where to collect one seller's goods. */
@Schema(description = "Collect this seller's goods at this point of theirs")
public record CollectionPointChoice(

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54") @NotNull
        UUID merchantId,

        @Schema(description = "One of the seller's points, from their profile's `collectionPoints`",
                example = "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e") @NotNull
        UUID collectionPointId) {
}
