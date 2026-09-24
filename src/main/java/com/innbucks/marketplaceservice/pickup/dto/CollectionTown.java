package com.innbucks.marketplaceservice.pickup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A town a seller can be collected from — what a catalogue card shows. */
@Schema(description = "A town where this seller has a collection point")
public record CollectionTown(

        @Schema(example = "harare")
        String townCode,

        @Schema(example = "Harare")
        String townName) {
}
