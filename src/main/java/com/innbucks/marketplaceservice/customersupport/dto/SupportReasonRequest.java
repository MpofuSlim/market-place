package com.innbucks.marketplaceservice.customersupport.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Why support is doing this. Kept as a note on the order.")
public record SupportReasonRequest(
        @NotBlank
        @Size(max = 255)
        @Schema(description = "Plain text, 1-255 characters. Recorded as a support note on the order; on a "
                + "parcel cancel the seller is shown it too.",
                example = "Buyer called: ordered the wrong size, asked to cancel before it ships.")
        String reason) {
}
