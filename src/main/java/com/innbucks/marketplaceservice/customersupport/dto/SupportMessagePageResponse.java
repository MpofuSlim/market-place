package com.innbucks.marketplaceservice.customersupport.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One page of support messages, newest first")
public record SupportMessagePageResponse(
        List<SupportMessageResponse> items,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "3") long totalItems,
        @Schema(example = "1") int totalPages) {
}
