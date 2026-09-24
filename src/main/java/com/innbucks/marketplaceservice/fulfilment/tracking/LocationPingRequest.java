package com.innbucks.marketplaceservice.fulfilment.tracking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** One position report from the courier's phone. */
@Schema(description = "The courier's current position, as the phone's GPS reports it")
public record LocationPingRequest(

        @Schema(description = "WGS84 degrees", example = "-17.829220")
        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double latitude,

        @Schema(description = "WGS84 degrees", example = "31.053961")
        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double longitude,

        @Schema(description = "The phone's accuracy estimate in metres (the browser's "
                + "coords.accuracy). Optional.", example = "12", nullable = true)
        @Min(0)
        @Max(100_000)
        Integer accuracyMeters,

        @Schema(description = "When the phone took the fix (the browser's position.timestamp). "
                + "Optional; a time in the future is treated as now.",
                example = "2026-09-24T12:14:05Z", nullable = true)
        Instant recordedAt) {
}
