package com.innbucks.marketplaceservice.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** Stable page envelope for the dispute queue. */
@Schema(description = "One page of disputes, oldest first")
public record DisputePageResponse(

        List<DisputeResponse> items,

        @Schema(example = "0")
        int page,

        @Schema(example = "20")
        int size,

        @Schema(example = "1")
        long totalItems,

        @Schema(example = "1")
        int totalPages) {

    public static DisputePageResponse from(Page<DisputeResponse> page) {
        return new DisputePageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
