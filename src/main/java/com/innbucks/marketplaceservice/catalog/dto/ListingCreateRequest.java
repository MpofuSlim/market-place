package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create payload for a merchant listing. Deliberately carries NO currency (the
 * cell currency is applied server-side); merchant/shop scope is taken from the
 * JWT claims — a client can never price in a foreign currency or list under
 * another merchant. The optional {@code merchantId} exists ONLY for the
 * SUPER_ADMIN on-behalf-creation exception (see
 * {@code ListingService.resolveCreateMerchantId}): a MERCHANT_ADMIN sending a
 * value different from their claim is refused 422.
 */
@Schema(description = "Create a merchant listing (starts as DRAFT)")
public record ListingCreateRequest(

        @Schema(description = "Display title. HTML is stripped server-side.",
                example = "Wireless Bluetooth Speaker", maxLength = 160)
        @NotBlank
        @Size(max = 160)
        String title,

        @Schema(description = "Free-text description. HTML is stripped server-side.",
                example = "Portable speaker with 12h battery life.", maxLength = 4000)
        @Size(max = 4000)
        String description,

        @Schema(description = "Category tag used for exact-match catalog filtering. HTML is stripped server-side.",
                example = "electronics", maxLength = 64)
        @Size(max = 64)
        String category,

        @Schema(description = "Unit price in MINOR units (cents). Currency is always the cell currency.",
                example = "2599", minimum = "1", maximum = "100000000")
        @NotNull
        @Min(1)
        @Max(100_000_000)
        Long priceCents,

        @Schema(description = "Units in stock available for reservation.",
                example = "120", minimum = "0", maximum = "1000000")
        @NotNull
        @Min(0)
        @Max(1_000_000)
        Integer stockQty,

        @Schema(description = "MERCHANT_ADMIN callers: OMIT this — your merchant scope comes from "
                + "your JWT automatically, and sending a different value is refused with 422 "
                + "merchant_scope_mismatch. SUPER_ADMIN only: REQUIRED, names the merchant the "
                + "listing is created on behalf of (admin tokens carry no merchantId claim) — "
                + "the one deliberate exception to merchant-scope-from-JWT.",
                nullable = true)
        UUID merchantId
) {
}
