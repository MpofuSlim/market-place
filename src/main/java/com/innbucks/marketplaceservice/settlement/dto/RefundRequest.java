package com.innbucks.marketplaceservice.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The operator recording a refund they have already sent. */
@Schema(description = "Record a refund you have made for a parcel the seller could not supply")
public record RefundRequest(

        @Schema(description = "Your transfer reference for the refund. Recorded, not executed - "
                + "this service moves no money, and a reference is what the buyer can be shown "
                + "as proof their money went back.", example = "RFND-2026-09-18-03")
        @NotBlank
        @Size(max = 64)
        String refundReference) {
}
