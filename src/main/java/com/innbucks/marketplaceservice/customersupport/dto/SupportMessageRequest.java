package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.messaging.MessageChannel;
import com.innbucks.marketplaceservice.customersupport.messaging.RecipientRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "A message an agent typed, to a customer on record. There is no destination "
        + "number: the record supplies it.")
public record SupportMessageRequest(
        @NotNull
        @Schema(description = "BUYER or ORDER", example = "ORDER")
        SubjectKind subjectKind,

        @NotNull
        @Schema(description = "The buyerUuid or the order id", example = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d")
        UUID subjectId,

        @Schema(description = "Whose number on the record. On an ORDER: BUYER (the payer, default), "
                + "GIFT_RECIPIENT or DELIVERY_RECIPIENT. On a BUYER: BUYER only.",
                example = "BUYER", nullable = true)
        RecipientRole recipient,

        @Size(max = 32)
        @Schema(description = "On a BUYER only: which of the buyer's OWN numbers (from their record's "
                + "`phones`). Omit for the most recently used. A number that is not theirs is refused.",
                example = "+263772123456", nullable = true)
        String phone,

        @Schema(description = "SMS, WHATSAPP, or SMS_THEN_WHATSAPP (default: WhatsApp only if the SMS fails)",
                example = "SMS_THEN_WHATSAPP", nullable = true)
        MessageChannel channel,

        @NotBlank
        @Size(max = 2000)
        @Schema(description = "Plain text. HTML is stripped; the support signature is added on its own line. "
                + "Links only to innbucks.co.zw (links survive only on WhatsApp - SMS cannot carry ':' or '/').",
                example = "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.")
        String text) {
}
