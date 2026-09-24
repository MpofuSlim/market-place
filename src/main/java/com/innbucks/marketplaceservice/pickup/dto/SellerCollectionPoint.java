package com.innbucks.marketplaceservice.pickup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Where one seller's goods on an order or quote are collected. {@code
 * collectionPoint} is absent when the seller has no point: collection is then
 * arranged with the seller directly, as every collection was before points.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One seller's collection point on this basket or order")
public record SellerCollectionPoint(

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "Absent when the seller has no collection point — then say "
                + "\"arrange collection with the seller\"", nullable = true)
        CollectionPointResponse collectionPoint) {
}
