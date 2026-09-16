package com.innbucks.marketplaceservice.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The operator's decision on one dispute — exactly once, one of two ways. */
@Schema(description = "Resolve a dispute: RELEASE the seller's money, or record a REFUND to the buyer")
public record ResolveDisputeRequest(

        @Schema(description = "RELEASE = the seller was right, the money clears for their next "
                + "payout run. REFUND = the buyer was right; the refund is RECORDED here and "
                + "EXECUTED by the operator on the payment rails (they have no reversal API this "
                + "service could call).", example = "REFUND")
        @NotNull
        Action action,

        @Schema(description = "Why — kept on the dispute and shown to nobody but operators.",
                example = "Courier photo shows the parcel left at the wrong address.", nullable = true)
        @Size(max = 500)
        String resolutionNote,

        @Schema(description = "REFUND only: the operator's transfer reference for the refund they "
                + "executed.", example = "RFND-2026-09-30-07", nullable = true)
        @Size(max = 64)
        String refundReference) {

    public enum Action {
        RELEASE,
        REFUND
    }
}
