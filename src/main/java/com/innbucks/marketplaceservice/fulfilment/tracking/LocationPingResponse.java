package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Whether a position report was stored. An ignored one is not an error. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Whether the position was stored")
public record LocationPingResponse(

        @Schema(description = "false when the report was ignored: sent within a few seconds of the "
                + "last one, older than the position already stored, or too old to be current. "
                + "Keep sending; nothing needs to be retried.", example = "true")
        boolean accepted,

        @Schema(description = "When the stored position was taken", example = "2026-09-24T12:14:05Z",
                nullable = true)
        Instant lastLocationAt) {
}
