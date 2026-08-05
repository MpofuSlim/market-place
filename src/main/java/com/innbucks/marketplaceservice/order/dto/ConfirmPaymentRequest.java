package com.innbucks.marketplaceservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * S2S payment confirmation. {@code amountCents} is the amount the payments
 * service ACTUALLY collected — it is cross-checked against the order total
 * (the 100x guard) and a mismatch parks the order unconfirmed, it never
 * confirms. {@code paymentRef} doubles as the idempotency handle: a replayed
 * confirm with the same ref is a 200 no-op.
 */
@Schema(description = "Internal S2S payment confirmation")
public record ConfirmPaymentRequest(

        @Schema(description = "The payments service's reference for the collected payment",
                example = "INB-PAY-20260805-000123")
        @NotBlank
        @Size(max = 64)
        String paymentRef,

        @Schema(description = "Amount actually collected, in cents — must equal the order total",
                example = "3550")
        @NotNull
        @Positive
        Long amountCents) {
}
