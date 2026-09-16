package com.innbucks.marketplaceservice.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** Stable page envelope (flat record, never Spring's {@code PageImpl} —
 *  fleet stance). */
@Schema(description = "One page of settlements")
public record SettlementPageResponse(

        List<SettlementResponse> items,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(example = "20")
        int size,

        @Schema(example = "3")
        long totalItems,

        @Schema(example = "1")
        int totalPages) {

    public static SettlementPageResponse from(Page<SettlementResponse> page) {
        return new SettlementPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
