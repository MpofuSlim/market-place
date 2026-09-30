package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessage;
import com.innbucks.marketplaceservice.notify.MsisdnMasking;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A message support sent a customer (or tried to), and what became of it")
public record SupportMessageResponse(
        @Schema(example = "8c2e4f61-9a3b-4d7e-b5c1-2f6a8d0e3b94")
        UUID id,

        @Schema(description = "CUSTOM (typed by an agent), ORDER_CONFIRMATION, PARCEL_UPDATE or COLLECT_CODE",
                example = "CUSTOM")
        String kind,

        @Schema(description = "What it is about: BUYER or ORDER", example = "ORDER")
        String subjectKind,

        @Schema(example = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d")
        String subjectId,

        @Schema(description = "SMS, WHATSAPP or SMS_THEN_WHATSAPP", example = "SMS_THEN_WHATSAPP")
        String channelRequested,

        @Schema(description = "SMS or WHATSAPP when it went out, else null", example = "SMS", nullable = true)
        String deliveredVia,

        @Schema(description = "PENDING, SENT or FAILED. PENDING on an old row means the service stopped "
                + "mid-send: it may or may not have gone out.", example = "SENT")
        String outcome,

        @Schema(description = "BUYER, GIFT_RECIPIENT or DELIVERY_RECIPIENT", example = "BUYER")
        String recipientRole,

        @Schema(description = "The number it went to, masked", example = "****3456")
        String recipient,

        @Schema(description = "Exactly what was sent. Null for a COLLECT_CODE message, whose code nobody on "
                + "the console may read.", nullable = true,
                example = "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\n"
                        + "- InnBucks Marketplace Support")
        String text,

        SupportAgent sentBy,

        @Schema(example = "2026-09-30T08:41:12Z")
        Instant createdAt,

        @Schema(example = "2026-09-30T08:41:13Z", nullable = true)
        Instant completedAt,

        @Schema(description = "Why it failed: sms_failed, whatsapp_failed, sms_and_whatsapp_failed, "
                + "not_minted", nullable = true, example = "sms_and_whatsapp_failed")
        String failureCode) {

    public static SupportMessageResponse from(SupportMessage m) {
        return new SupportMessageResponse(m.getId(), m.getKind(), m.getSubjectKind(), m.getSubjectId(),
                m.getChannelRequested(), m.getDeliveredVia(), m.getOutcome(), m.getRecipientRole(),
                MsisdnMasking.mask(m.getRecipientMsisdn()), m.getBody(),
                new SupportAgent(m.getAgentUuid(), m.getAgentLogin()), m.getCreatedAt(), m.getCompletedAt(),
                m.getFailureCode());
    }
}
