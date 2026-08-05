package com.innbucks.marketplaceservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable page envelope for order collections. A deliberate flat record instead
 * of serialising Spring Data's {@code PageImpl} directly — that shape is
 * unsupported-for-serialisation and shifts between Spring Data versions, and
 * the FE contract must not (same stance as the catalog's
 * {@code ListingPageResponse}).
 */
@Schema(description = "One page of the buyer's orders")
public record OrderPageResponse(

        List<OrderResponse> items,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(description = "Page size actually applied (hard-capped fleet-wide)", example = "20")
        int size,

        @Schema(description = "Total orders across all pages", example = "1")
        long totalItems,

        @Schema(example = "1")
        int totalPages
) {

    public static OrderPageResponse from(Page<OrderResponse> page) {
        return new OrderPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
