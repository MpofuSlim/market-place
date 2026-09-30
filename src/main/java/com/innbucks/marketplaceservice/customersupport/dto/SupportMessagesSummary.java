package com.innbucks.marketplaceservice.customersupport.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The head of a subject's support messages: how many, and the newest few")
public record SupportMessagesSummary(
        @Schema(example = "1")
        long total,

        @Schema(description = "Newest first; the full list is GET /marketplace/support/messages")
        List<SupportMessageResponse> latest) {
}
