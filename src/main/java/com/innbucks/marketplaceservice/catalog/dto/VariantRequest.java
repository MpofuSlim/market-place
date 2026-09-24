package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** One option in a listing create/update (V19). */
@Schema(description = "One option of a listing: its values, and optionally its own price and stock")
public record VariantRequest(

        @Schema(description = "UPDATE only: the id of an existing option this entry keeps. An entry "
                + "without id keeps the existing option with the same values (case-insensitive), "
                + "or is a new option. Existing options no entry keeps are REMOVED. Keeping an id "
                + "while changing its values re-points every shopper's cart line to the new values "
                + "- use that to fix a typo; a different size is a new entry without an id.",
                example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", nullable = true)
        UUID id,

        @Schema(description = "One value per option axis, in the order of `options`. At most 40 "
                + "characters each; a comma is refused.", example = "[\"M\", \"Black\"]")
        @NotNull
        @Size(min = 1, max = 2)
        List<String> values,

        @Schema(description = "This option's own price in MINOR units, only when it costs MORE than "
                + "the listing's `priceCents` (which must be the lowest option price). Omit, or send "
                + "the listing price, for an option that sells at the listing price.",
                example = "2299", minimum = "1", maximum = "100000000", nullable = true)
        @Min(1)
        @Max(100_000_000)
        Long priceCents,

        @Schema(description = "Units of this option in stock. REQUIRED for a new option; on an "
                + "option this entry keeps, omit it to leave that option's stock (and its live "
                + "reservations) alone.",
                example = "4", minimum = "0", maximum = "1000000", nullable = true)
        @Min(0)
        @Max(1_000_000)
        Integer stockQty
) {
}
