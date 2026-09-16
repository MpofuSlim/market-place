package com.innbucks.marketplaceservice.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/replace payload for one address-book entry. Every free-text field is
 * jsoup-sanitized server-side (stored-XSS defence) and the msisdn is
 * normalised to E.164 before storage — a client sends what the shopper typed.
 */
@Schema(description = "A delivery destination to save in the buyer's address book")
public record AddressRequest(

        @Schema(description = "The shopper's own name for this entry. Optional.",
                example = "Home", nullable = true)
        @Size(max = 40)
        String label,

        @Schema(description = "Who receives the parcel — not necessarily the buyer.",
                example = "Tariro Moyo")
        @NotBlank
        @Size(max = 120)
        String recipientName,

        @Schema(description = "The number the courier rings. Normalised to E.164 "
                + "(default region = the deployment country).", example = "+263771234567")
        @NotBlank
        @Size(max = 32)
        String recipientMsisdn,

        @Schema(example = "14 Samora Machel Ave")
        @NotBlank
        @Size(max = 160)
        String line1,

        @Schema(example = "Flat 3B", nullable = true)
        @Size(max = 160)
        String line2,

        @Schema(example = "Harare")
        @NotBlank
        @Size(max = 80)
        String city,

        @Schema(description = "Neighbourhood/suburb", example = "Avondale", nullable = true)
        @Size(max = 80)
        String area,

        @Schema(description = "What actually gets a courier to the door.",
                example = "Opposite the clinic, blue gate", nullable = true)
        @Size(max = 160)
        String landmark,

        @Schema(description = "Make this the buyer's default destination. The previous default is "
                + "demoted in the same transaction. The FIRST address a buyer saves becomes the "
                + "default whatever this says — a book with one entry and no default helps nobody.",
                example = "true", nullable = true)
        Boolean makeDefault) {
}
