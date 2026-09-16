package com.innbucks.marketplaceservice.fulfilment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable page envelope for a seller's fulfilment queue — a flat record rather
 * than Spring Data's {@code PageImpl}, whose serialised shape is unsupported
 * and shifts between versions (same stance as {@code OrderPageResponse}).
 */
@Schema(description = "One page of a seller's fulfilment queue")
public record MerchantFulfilmentPageResponse(

        List<MerchantFulfilmentResponse> items,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(description = "Page size actually applied", example = "20")
        int size,

        @Schema(example = "3")
        long totalItems,

        @Schema(example = "1")
        int totalPages) {

    public static MerchantFulfilmentPageResponse from(Page<MerchantFulfilmentResponse> page) {
        return new MerchantFulfilmentPageResponse(page.getContent(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
