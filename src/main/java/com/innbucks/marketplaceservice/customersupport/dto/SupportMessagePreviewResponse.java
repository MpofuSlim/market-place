package com.innbucks.marketplaceservice.customersupport.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What the customer would receive. Nothing is sent or recorded.")
public record SupportMessagePreviewResponse(
        @Schema(example = "SMS_THEN_WHATSAPP")
        String channel,

        @Schema(example = "BUYER")
        String recipientRole,

        @Schema(description = "The number it would go to, masked", example = "****3456")
        String recipient,

        @Schema(description = "The exact text of the first channel tried: the GSM-safe SMS text, or the "
                + "WhatsApp text on a WHATSAPP send",
                example = "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\n"
                        + "- InnBucks Marketplace Support")
        String text,

        @Schema(description = "Characters in `text`", example = "108")
        int characters,

        @Schema(description = "The limit `text` is held to", example = "459")
        int maxCharacters,

        @Schema(description = "SMS segments it will be billed as; null on a WHATSAPP send",
                example = "1", nullable = true)
        Integer smsSegments,

        @Schema(description = "True when making the text SMS-safe changed it (an em-dash, a colon, an "
                + "emoji...). Read `text` before sending.", example = "false")
        boolean transliterated,

        @Schema(description = "On SMS_THEN_WHATSAPP only: the text WhatsApp would carry if the SMS fails "
                + "(the original, unchanged)", nullable = true,
                example = "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\n"
                        + "- InnBucks Marketplace Support")
        String whatsappText) {
}
