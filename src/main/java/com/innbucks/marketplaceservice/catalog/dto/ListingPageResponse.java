package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable page envelope for listing collections. A deliberate flat record
 * instead of serialising Spring Data's {@code PageImpl} directly — that shape
 * is unsupported-for-serialisation and shifts between Spring Data versions,
 * and the FE contract must not.
 */
@Schema(description = "One page of listings")
public record ListingPageResponse(

        List<ListingResponse> items,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(description = "Page size actually applied (requested size is clamped to 50)", example = "20")
        int size,

        @Schema(description = "Total matching listings across all pages", example = "1")
        long totalItems,

        @Schema(example = "1")
        int totalPages
) {

    public static ListingPageResponse from(Page<ListingResponse> page) {
        return new ListingPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
