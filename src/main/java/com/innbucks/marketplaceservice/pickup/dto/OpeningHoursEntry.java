package com.innbucks.marketplaceservice.pickup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.DayOfWeek;

/**
 * One open interval on one weekday, in the MARKET's local time. A shop that
 * closes for lunch sends two entries for that day. Days with no entry are
 * closed.
 */
@Schema(description = "One open interval, market-local wall-clock time (Harare time on the ZW "
        + "cell). Two entries for one day = open twice that day (e.g. closed for lunch).")
public record OpeningHoursEntry(

        @Schema(example = "MONDAY") @NotNull
        DayOfWeek day,

        @Schema(description = "24-hour HH:mm", example = "08:00")
        @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "must be HH:mm (24-hour)")
        String opens,

        @Schema(description = "24-hour HH:mm, after `opens`", example = "17:00")
        @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "must be HH:mm (24-hour)")
        String closes) {
}
