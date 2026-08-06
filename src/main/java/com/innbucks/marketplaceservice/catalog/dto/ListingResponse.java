package com.innbucks.marketplaceservice.catalog.dto;

import com.innbucks.marketplaceservice.catalog.ItemCondition;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Listing view shared by the merchant surface and the public catalog.
 * {@code merchantId} is deliberately public (buyers may filter by seller
 * later); {@code shopId} and the optimistic-lock version are internal and
 * never leave the service.
 *
 * <p>Built ONLY via {@link #from(Listing, List, String)} with the gallery's
 * bytes-free {@link ImageMeta} projection — assembly never loads image bytes.
 * {@code ListingViewAssembler} owns fetching that metadata (one grouped query
 * per page, never per-row).
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

        @Schema(description = "Curated taxonomy code (see GET /marketplace/categories)",
                example = "tv-audio")
        String categoryCode,

        @Schema(description = "Display name of the category", example = "TV & Audio")
        String categoryName,

        @Schema(description = "Item condition", example = "NEW")
        ItemCondition condition,

        @Schema(description = "Seller-declared city (optional)", example = "Harare", nullable = true)
        String city,

        @Schema(description = "Neighbourhood/area within the city (optional)",
                example = "Avondale", nullable = true)
        String area,

        @Schema(description = "Unit price in MINOR units (cents)", example = "2599")
        long priceCents,

        @Schema(description = "ISO-4217 cell currency", example = "USD")
        String currency,

        @Schema(example = "120")
        int stockQty,

        @Schema(example = "ACTIVE")
        ListingStatus status,

        @Schema(description = "Average verified-purchase rating, one decimal; null when the "
                + "listing has no reviews yet", example = "5.0", nullable = true)
        Double ratingAvg,

        @Schema(description = "Number of verified-purchase reviews", example = "1")
        int reviewCount,

        @Schema(description = "UTC instant", example = "2026-08-05T09:15:00Z")
        Instant createdAt,

        @Schema(description = "UTC instant", example = "2026-08-05T09:15:00Z")
        Instant updatedAt,

        @Schema(description = "Public URL serving the PRIMARY image bytes (no auth, GET only); "
                + "null when the gallery has no primary image. Back-compat alias of imageUrls[0].",
                example = "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                nullable = true)
        String imageUrl,

        @ArraySchema(arraySchema = @Schema(
                description = "Public per-image URLs for the whole gallery, primary FIRST then "
                        + "append order. Empty when no images have been uploaded."),
                schema = @Schema(example = "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"))
        List<String> imageUrls
) {

    /**
     * @param images gallery metadata in serve order (primary first) — the
     *               bytes-free projection, so this can run on a 50-row page
     *               without touching a single image byte
     * @param categoryName resolved display name for {@code categoryCode}
     */
    public static ListingResponse from(Listing listing, List<ImageMeta> images, String categoryName) {
        boolean hasPrimary = images.stream().anyMatch(ImageMeta::isPrimaryImage);
        List<String> urls = images.stream()
                .map(meta -> "/marketplace/catalog/" + listing.getId() + "/images/" + meta.getId())
                .toList();
        return new ListingResponse(
                listing.getId(),
                listing.getMerchantId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getCategoryCode(),
                categoryName,
                listing.getCondition(),
                listing.getCity(),
                listing.getArea(),
                listing.getPriceCents(),
                listing.getCurrency(),
                listing.getStockQty(),
                listing.getStatus(),
                ratingAvg(listing),
                listing.getRatingCount(),
                listing.getCreatedAt(),
                listing.getUpdatedAt(),
                hasPrimary
                        ? "/marketplace/catalog/" + listing.getId() + "/image"
                        : null,
                urls);
    }

    /** One-decimal average from the denormalized V5 aggregates — zero extra
     *  queries; null (never 0.0) when there are no reviews, so "unrated" is
     *  distinguishable from "rated terribly". */
    private static Double ratingAvg(Listing listing) {
        int count = listing.getRatingCount();
        if (count <= 0) {
            return null;
        }
        return Math.round(listing.getRatingSum() * 10.0 / count) / 10.0;
    }
}
