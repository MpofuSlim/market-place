package com.innbucks.marketplaceservice.catalog.dto;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Listing view shared by the merchant surface and the public catalog.
 * {@code merchantId} is deliberately public (buyers may filter by seller
 * later); {@code shopId} and the optimistic-lock version are internal and
 * never leave the service.
 */
@Schema(description = "A marketplace listing")
public record ListingResponse(

        @Schema(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
        UUID id,

        @Schema(description = "Owning merchant id (from the seller's JWT scope)",
                example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(example = "Wireless Bluetooth Speaker")
        String title,

        @Schema(example = "Portable speaker with 12h battery life.")
        String description,

        @Schema(example = "electronics")
        String category,

        @Schema(description = "Unit price in MINOR units (cents)", example = "2599")
        long priceCents,

        @Schema(description = "ISO-4217 cell currency", example = "USD")
        String currency,

        @Schema(example = "120")
        int stockQty,

        @Schema(example = "ACTIVE")
        ListingStatus status,

        @Schema(description = "UTC instant", example = "2026-08-05T09:15:00Z")
        Instant createdAt,

        @Schema(description = "UTC instant", example = "2026-08-05T09:15:00Z")
        Instant updatedAt
) {

    public static ListingResponse from(Listing listing) {
        return new ListingResponse(
                listing.getId(),
                listing.getMerchantId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getCategory(),
                listing.getPriceCents(),
                listing.getCurrency(),
                listing.getStockQty(),
                listing.getStatus(),
                listing.getCreatedAt(),
                listing.getUpdatedAt());
    }
}
