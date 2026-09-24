package com.innbucks.marketplaceservice.pickup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A collection point, as every surface shows it: the seller's portal, the
 * public seller profile, the buyer's order, parcel, tracking and collection
 * code screens. One shape, so a screen can reuse one component.
 *
 * <p>On an ORDER it is the snapshot taken when the order was placed: the
 * address never moves under a buyer. {@code id} is then the point it came
 * from, and the hours are that point's CURRENT hours (absent once the seller
 * has removed it), because "when can I come" is a question about today.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Where to collect, with the seller's opening hours in market-local time")
public record CollectionPointResponse(

        @Schema(example = "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e")
        UUID id,

        @Schema(example = "Avondale shop")
        String name,

        @Schema(example = "harare")
        String townCode,

        @Schema(example = "Harare")
        String townName,

        @Schema(example = "14 Samora Machel Ave")
        String line1,

        @Schema(example = "Shop 3, Avondale Shopping Centre", nullable = true)
        String line2,

        @Schema(example = "Avondale", nullable = true)
        String area,

        @Schema(example = "Next to the pharmacy", nullable = true)
        String landmark,

        @Schema(description = "E.164", example = "+263242123456", nullable = true)
        String phone,

        @Schema(example = "Closed on public holidays", nullable = true)
        String hoursNote,

        @Schema(example = "-17.798500", nullable = true)
        BigDecimal latitude,

        @Schema(example = "31.045200", nullable = true)
        BigDecimal longitude,

        @Schema(description = "Weekly hours, Monday first. Absent when the seller has not given "
                + "any. When present, a day that is not listed is closed.", nullable = true)
        List<OpeningHoursEntry> openingHours,

        @Schema(description = "The hours as one line, ready to print as-is",
                example = "Mon-Fri 08:00-17:00, Sat 08:00-13:00", nullable = true)
        String openingHoursSummary,

        @Schema(description = "Whether it is open right now in the market's time. Absent when no "
                + "hours are given.", example = "true", nullable = true)
        Boolean openNow,

        @Schema(description = "The seller's default point — where a buyer who does not choose "
                + "collects. Absent on an order's snapshot.", example = "true", nullable = true)
        Boolean defaultPoint,

        @Schema(example = "2026-09-24T08:10:22Z", nullable = true)
        Instant updatedAt) {
}
