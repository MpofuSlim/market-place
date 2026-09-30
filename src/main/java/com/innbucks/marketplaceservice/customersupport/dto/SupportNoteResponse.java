package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "An internal support note")
public record SupportNoteResponse(
        @Schema(example = "7c1e4b92-8d3a-4f6e-b5a1-2c9d0e8f7a63")
        UUID id,

        @Schema(example = "ORDER")
        String subjectKind,

        @Schema(example = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d")
        String subjectId,

        @Schema(example = "Buyer called: the courier left a missed-call. Asked the seller to re-attempt tomorrow.")
        String body,

        SupportAgent createdBy,

        Instant createdAt) {
}
