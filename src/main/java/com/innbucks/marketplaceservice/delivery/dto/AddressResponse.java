package com.innbucks.marketplaceservice.delivery.dto;

import com.innbucks.marketplaceservice.delivery.DeliveryAddress;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One saved address, as the buyer's address picker renders it. */
@Schema(description = "A saved delivery destination")
public record AddressResponse(

        @Schema(example = "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45")
        UUID id,

        @Schema(example = "Home", nullable = true)
        String label,

        @Schema(example = "Tariro Moyo")
        String recipientName,

        @Schema(description = "E.164", example = "+263771234567")
        String recipientMsisdn,

        @Schema(example = "14 Samora Machel Ave")
        String line1,

        @Schema(example = "Flat 3B", nullable = true)
        String line2,

        @Schema(example = "Harare")
        String city,

        @Schema(example = "Avondale", nullable = true)
        String area,

        @Schema(example = "Opposite the clinic, blue gate", nullable = true)
        String landmark,

        @Schema(description = "Pre-selected at checkout when the buyer names no address",
                example = "true")
        boolean defaultAddress,

        @Schema(example = "2026-09-12T08:10:22Z")
        Instant createdAt,

        @Schema(example = "2026-09-12T08:10:22Z")
        Instant updatedAt,

        @Schema(description = "The town code. Null on an old address whose city matched no town — "
                + "edit it before using it for delivery.", example = "harare", nullable = true)
        String townCode) {

    public static AddressResponse from(DeliveryAddress a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getRecipientName(),
                a.getRecipientMsisdn(), a.getLine1(), a.getLine2(), a.getCity(), a.getArea(),
                a.getLandmark(), a.isDefaultAddress(), a.getCreatedAt(), a.getUpdatedAt(),
                a.getTownCode());
    }
}
