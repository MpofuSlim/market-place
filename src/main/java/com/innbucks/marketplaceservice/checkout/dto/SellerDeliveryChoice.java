package com.innbucks.marketplaceservice.checkout.dto;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The buyer's choice of how ONE seller's goods reach them (V20), on the quote
 * and the order alike: an entry in {@code sellerDeliveryMethods}. A seller the
 * list does not name takes the request's {@code deliveryMethod}.
 */
@Schema(description = "Receive this seller's goods by this method")
public record SellerDeliveryChoice(

        @Schema(description = "A seller in the basket - `sellers[].merchantId` from the quote",
                example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        @NotNull
        UUID merchantId,

        @Schema(description = "How this seller's goods reach you. Must be one the cell offers; "
                + "read `sellers[].availableMethods` on the quote for what this seller can do.",
                example = "DELIVERY")
        @NotNull
        DeliveryMethod deliveryMethod) {
}
