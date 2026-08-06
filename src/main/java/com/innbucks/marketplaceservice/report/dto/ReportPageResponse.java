package com.innbucks.marketplaceservice.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** Stable page envelope for the moderation queue (the ListingPageResponse
 *  rationale: never serialize Spring Data's PageImpl directly). */
@Schema(description = "One page of listing reports, oldest first (FIFO queue)")
public record ReportPageResponse(

        List<ReportResponse> items,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(description = "Page size actually applied (requested size is clamped to 50)",
                example = "20")
        int size,

        @Schema(description = "Total reports matching the status filter", example = "1")
        long totalItems,

        @Schema(example = "1")
        int totalPages
) {

    public static ReportPageResponse from(Page<ReportResponse> page) {
        return new ReportPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
