package com.innbucks.marketplaceservice.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Turn collection on or off for a seller (V20).
 *
 * <p>A boxed {@link Boolean} with {@code @NotNull} rather than a primitive: a
 * primitive would read a missing field as {@code false} and silently make the
 * seller delivery-only on a malformed request.
 */
@Schema(description = "Whether buyers may collect from this seller. `false` makes the seller "
        + "DELIVERY-ONLY: every item must then be delivered, so every item on sale needs at "
        + "least one delivery town.")
public record CollectionSettingRequest(

        @Schema(description = "true = buyers may collect (the default for every seller); "
                + "false = delivery only", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Boolean collectionEnabled) {
}
