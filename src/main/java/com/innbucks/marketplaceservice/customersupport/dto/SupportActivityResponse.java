package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "One support activity row: who looked at or did what, when")
public record SupportActivityResponse(
        UUID id,

        SupportAgent agent,

        @Schema(description = "SEARCH, VIEW_BUYER, VIEW_BUYER_ORDERS, VIEW_ORDER, VIEW_SELLER, "
                + "VIEW_SELLER_PARCELS, NOTE_ADDED, and the action names support actions add. "
                + "Render an unknown value as-is.", example = "VIEW_ORDER")
        String action,

        @Schema(description = "BUYER, ORDER or SELLER; null for a search", example = "ORDER")
        String subjectKind,

        @Schema(example = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d")
        String subjectId,

        @Schema(description = "Ids and enums describing the row. Never free text; a phone number "
                + "appears masked.", example = "{\"orderRef\":\"MKT-8B3E5D7F9A1C\"}")
        Map<String, Object> detail,

        Instant createdAt) {
}
