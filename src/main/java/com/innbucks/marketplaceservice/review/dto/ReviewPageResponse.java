package com.innbucks.marketplaceservice.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** Stable page envelope for the public review list (the ListingPageResponse
 *  rationale: never serialize Spring Data's PageImpl directly). */
@Schema(description = "One page of a listing's reviews, newest first")
public record ReviewPageResponse(

        List<PublicReviewResponse> items,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(description = "Page size actually applied (requested size is clamped to 50)",
                example = "20")
        int size,

        @Schema(description = "Total reviews across all pages", example = "1")
        long totalItems,

        @Schema(example = "1")
        int totalPages
) {

    public static ReviewPageResponse from(Page<PublicReviewResponse> page) {
        return new ReviewPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
