package com.innbucks.marketplaceservice.delivery.dto;

import com.innbucks.marketplaceservice.delivery.DeliveryTown;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A town sellers can deliver to")
public record DeliveryTownResponse(

        @Schema(description = "Stable code to send as townCode", example = "bulawayo")
        String code,

        @Schema(example = "Bulawayo")
        String name,

        @Schema(example = "Bulawayo")
        String province) {

    public static DeliveryTownResponse from(DeliveryTown town) {
        return new DeliveryTownResponse(town.getCode(), town.getName(), town.getProvince());
    }
}
