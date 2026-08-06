package com.innbucks.marketplaceservice.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /marketplace/reports/{id} — closes an OPEN report. */
@Schema(description = "Moderation decision on an open report")
public record ReportResolutionRequest(

        @NotNull(message = "action is required")
        @Schema(description = "RESOLVE (the report was founded) or DISMISS (it was not)",
                example = "RESOLVE")
        Action action,

        @Size(max = 500, message = "resolutionNote must be at most 500 characters")
        @Schema(description = "Optional moderator note (HTML is stripped before storage)",
                example = "Confirmed counterfeit; listing deactivated.", maxLength = 500,
                nullable = true)
        String resolutionNote,

        @Schema(description = "RESOLVE only: also set the reported listing INACTIVE (deactivation "
                + "is always allowed — no publish-gate concern). Defaults to false; refused on "
                + "DISMISS.", example = "true", defaultValue = "false", nullable = true)
        Boolean deactivateListing
) {

    /** Moderation actions — deliberately NOT the report status enum: a client
     *  can never PATCH a report back to OPEN. */
    public enum Action { RESOLVE, DISMISS }

    public boolean deactivate() {
        return Boolean.TRUE.equals(deactivateListing);
    }
}
