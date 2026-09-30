package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "An internal note on a buyer, order or seller. Append-only.")
public record SupportNoteRequest(
        @NotNull
        @Schema(description = "BUYER, ORDER or SELLER", example = "ORDER")
        SubjectKind subjectKind,

        @NotNull
        @Schema(description = "The buyerUuid, order id or merchantId", example = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d")
        UUID subjectId,

        @NotBlank
        @Size(max = 2000)
        @Schema(description = "Plain text, 1-2000 characters. HTML is stripped.",
                example = "Buyer called: the courier left a missed-call. Asked the seller to re-attempt tomorrow.")
        String body) {
}
