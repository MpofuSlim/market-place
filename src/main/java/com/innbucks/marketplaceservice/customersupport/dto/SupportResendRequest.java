package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.customersupport.messaging.MessageChannel;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How to send a platform message again. The body is optional.")
public record SupportResendRequest(
        @Schema(description = "SMS, WHATSAPP, or SMS_THEN_WHATSAPP (default)", example = "SMS_THEN_WHATSAPP",
                nullable = true)
        MessageChannel channel) {
}
