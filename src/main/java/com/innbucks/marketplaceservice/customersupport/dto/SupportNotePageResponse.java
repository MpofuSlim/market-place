package com.innbucks.marketplaceservice.customersupport.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One page of support notes, newest first")
public record SupportNotePageResponse(
        List<SupportNoteResponse> items,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "2") long totalItems,
        @Schema(example = "1") int totalPages) {
}
