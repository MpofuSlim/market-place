package com.innbucks.marketplaceservice.pickup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A whole collection point. Create and replace take the same body: there is no
 * partial update, because "move just the address" and "rename it" are both
 * the same act of redefining where buyers go.
 */
@Schema(description = "Where buyers collect from you. Replaces the whole point on update.")
public record CollectionPointRequest(

        @Schema(description = "What buyers will look for", example = "Avondale shop")
        @NotBlank @Size(max = 80)
        String name,

        @Schema(description = "A code from GET /marketplace/delivery-towns", example = "harare")
        @NotBlank @Size(max = 40)
        String townCode,

        @Schema(example = "14 Samora Machel Ave") @NotBlank @Size(max = 160)
        String line1,

        @Schema(example = "Shop 3, Avondale Shopping Centre", nullable = true) @Size(max = 160)
        String line2,

        @Schema(example = "Avondale", nullable = true) @Size(max = 80)
        String area,

        @Schema(example = "Next to the pharmacy", nullable = true) @Size(max = 160)
        String landmark,

        @Schema(description = "The counter's number; a landline is fine", example = "0242123456",
                nullable = true) @Size(max = 20)
        String phone,

        @Schema(description = "What the weekly hours cannot say",
                example = "Closed on public holidays", nullable = true) @Size(max = 160)
        String hoursNote,

        @Schema(description = "Optional map pin: send both or neither", example = "-17.7985",
                nullable = true)
        Double latitude,

        @Schema(example = "31.0452", nullable = true)
        Double longitude,

        @Schema(description = "Weekly hours. Omit or send [] when you would rather not say; "
                + "then no day is shown as open or closed.")
        @Size(max = 14) @Valid
        List<OpeningHoursEntry> openingHours) {
}
