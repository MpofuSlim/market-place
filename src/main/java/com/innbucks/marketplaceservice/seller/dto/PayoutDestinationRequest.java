package com.innbucks.marketplaceservice.seller.dto;

import com.innbucks.marketplaceservice.seller.PayoutMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Where a seller wants their released money sent.
 *
 * <p>The per-method fields are deliberately NOT bean-validated as required —
 * which of them is required depends on {@code method}, and Bean Validation
 * cannot express that without a class-level validator whose message the client
 * cannot attach to a field. {@code SellerService.setPayoutDestination} checks
 * them and names the missing field, so the app can highlight the right input.
 */
@Schema(description = "A seller's payout destination — complete for the chosen method, or the "
        + "request is refused. Submitting one REPLACES the whole destination; there is no "
        + "partial update, because a half-changed destination is exactly the shape that reads "
        + "as configured and fails at transfer time.")
public record PayoutDestinationRequest(

        @Schema(description = "Which rail to pay on. MOBILE_MONEY needs `msisdn`; "
                + "BANK needs `bankName` + `accountNumber`.", example = "MOBILE_MONEY")
        @NotNull
        PayoutMethod method,

        @Schema(description = "The name the destination account is held in. NOT your trading "
                + "name — this is what the bank or wallet has on file, and a transfer is "
                + "rejected when it does not match.",
                example = "Rudo Chikwanha")
        @NotBlank
        @Size(max = 120)
        String accountName,

        @Schema(description = "MOBILE_MONEY only. Any spelling the cell's country accepts; "
                + "stored normalised to E.164.", example = "0771234567", nullable = true)
        @Size(max = 32)
        String msisdn,

        @Schema(description = "BANK only.", example = "CBZ Bank", nullable = true)
        @Size(max = 120)
        String bankName,

        @Schema(description = "BANK only. Not format-checked — account-number formats vary per "
                + "bank, and a guess that refuses a valid account is worse than no check.",
                example = "01123456789012", nullable = true)
        @Size(max = 40)
        String accountNumber) {
}
