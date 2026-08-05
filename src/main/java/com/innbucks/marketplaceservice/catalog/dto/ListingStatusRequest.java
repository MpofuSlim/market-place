package com.innbucks.marketplaceservice.catalog.dto;

import com.innbucks.marketplaceservice.catalog.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Move a listing to a new lifecycle status")
public record ListingStatusRequest(

        @Schema(description = "Target status. Only ACTIVE listings appear in the public catalog "
                + "and can have stock reserved.", example = "ACTIVE")
        @NotNull
        ListingStatus status
) {
}
