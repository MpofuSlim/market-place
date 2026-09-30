package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.settlement.DisputeReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A dispute support opens for a buyer who cannot open it themselves")
public record SupportDisputeRequest(
        @NotNull
        @Schema(description = "NOT_RECEIVED, DAMAGED, NOT_AS_DESCRIBED or WRONG_ITEM", example = "NOT_RECEIVED")
        DisputeReason reason,

        @NotBlank
        @Size(max = 1000)
        @Schema(description = "What the buyer told you, for the operator who decides it. Also kept as a "
                + "support note on the order.",
                example = "Buyer called: paid six days ago, courier never came, seller not answering.")
        String detail) {
}
