package com.innbucks.marketplaceservice.report.dto;

import com.innbucks.marketplaceservice.report.ReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /marketplace/catalog/{listingId}/report. */
@Schema(description = "A report flagging a listing to the moderation queue")
public record ReportRequest(

        @NotNull(message = "reason is required")
        @Schema(description = "Why the listing is being reported", example = "COUNTERFEIT",
                implementation = ReportReason.class)
        ReportReason reason,

        @Size(max = 500, message = "detail must be at most 500 characters")
        @Schema(description = "Optional free-text context for moderators (HTML is stripped "
                + "before storage)", example = "Logo is fake — the brand does not make this model.",
                maxLength = 500, nullable = true)
        String detail
) {
}
