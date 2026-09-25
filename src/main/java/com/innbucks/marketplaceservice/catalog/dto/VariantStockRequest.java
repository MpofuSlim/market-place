package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The quick restock of ONE option (V19): an absolute stock count. */
@Schema(description = "Set one option's stock")
public record VariantStockRequest(

        @Schema(description = "Units of this option now in stock (absolute, not added)",
                example = "12", minimum = "0", maximum = "1000000")
        @NotNull
        @Min(0)
        @Max(1_000_000)
        Integer stockQty
) {
}
