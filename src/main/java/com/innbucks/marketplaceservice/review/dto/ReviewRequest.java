package com.innbucks.marketplaceservice.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of both review writes (POST create and PUT /mine edit). The rating is
 * mandatory; the comment is optional and jsoup-sanitized before storage.
 * Validated on the RAW value ({@code TextSanitizer} output is never longer
 * than its input, so the 1000-char column bound holds post-sanitization).
 */
@Schema(description = "A verified-purchase review of a listing")
public record ReviewRequest(

        @NotNull(message = "rating is required")
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        @Schema(description = "Star rating, 1 (worst) to 5 (best)", example = "5",
                minimum = "1", maximum = "5")
        Integer rating,

        @Size(max = 1000, message = "comment must be at most 1000 characters")
        @Schema(description = "Optional free-text comment (HTML is stripped before storage)",
                example = "Great speaker, battery really does last all day.",
                maxLength = 1000, nullable = true)
        String comment
) {
}
