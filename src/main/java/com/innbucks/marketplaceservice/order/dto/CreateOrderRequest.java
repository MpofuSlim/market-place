package com.innbucks.marketplaceservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Order creation payload. Deliberately carries NO prices — unit prices and
 * totals are computed server-side from the listing rows, so a client can
 * never supply a price. Config-bound caps (line count, per-line quantity)
 * are enforced in the service, not here, because they come from
 * {@code marketplace.order.*} properties.
 */
@Schema(description = "Order creation request. Prices are never accepted from the client — "
        + "totals are computed server-side from the listing rows.")
public record CreateOrderRequest(

        @Schema(description = "Buyer contact number; normalised to E.164 before storage "
                + "(default region = the deployment country)", example = "+263771234567")
        @NotBlank
        @Size(max = 32)
        String buyerMsisdn,

        @Schema(description = "Order lines — one per listing, no duplicates")
        @NotEmpty
        @Valid
        List<Item> items) {

    @Schema(description = "One order line")
    public record Item(

            @Schema(description = "Listing to buy", example = "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d")
            @NotNull
            UUID listingId,

            @Schema(description = "Units to buy (capped by marketplace.order.max-quantity-per-item)",
                    example = "2")
            @NotNull
            @Min(1)
            Integer quantity) {
    }
}
