package com.innbucks.marketplaceservice.settlement.dto;

import com.innbucks.marketplaceservice.settlement.DisputeReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The buyer's dispute: a bounded reason plus their own words. */
@Schema(description = "Dispute a parcel — freezes the seller's money until an operator decides")
public record DisputeRequest(

        @Schema(description = "Why. NOT_RECEIVED needs no delivered parcel — \"it never arrived\" "
                + "is exactly the dispute.", example = "NOT_RECEIVED")
        @NotNull
        DisputeReason reason,

        @Schema(description = "The buyer's own words, shown to the operator. Sanitized server-side.",
                example = "Paid five days ago, the seller has stopped answering.", nullable = true)
        @Size(max = 1000)
        String detail) {
}
