package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The public seller header: everything needed to render "who am I buying
 * from" in ONE call.
 *
 * <p><b>Why it exists.</b> Every field here was already served, but from three
 * different places — the badge rides each listing, the rating sits on
 * {@code /catalog/merchants/{id}/rating}, and the listing count could only be
 * had by paging the catalogue. A seller page therefore cost three round trips
 * and a client-side join, which is why the app rendered a bare UUID instead.
 *
 * <p><b>It never 404s.</b> An unknown merchant id answers with an unverified,
 * nameless profile and zeroes, exactly as the sibling rating endpoint does. A
 * 404 here would turn the public catalogue into an oracle for which merchant
 * ids exist, and a shopper following a stale link is better served by an empty
 * profile than an error page.
 *
 * <p><b>Nothing here is invented.</b> The app team's proposal also asked for a
 * logo, a response time and a return policy; this service stores none of the
 * three and no other service in the fleet does either, so they are absent
 * rather than defaulted. A fabricated "responds within 24h" is a promise the
 * platform has no basis to make.
 */
@Schema(description = "Public profile of one seller")
public record MerchantProfileResponse(

        @Schema(description = "Fleet merchant id", example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "Trading name, when the platform has recorded one. Null otherwise — "
                + "render the seller without a name rather than inventing one.",
                example = "Rudo Traders", nullable = true)
        String displayName,

        @Schema(description = "Whether the platform has vetted this seller (APPROVED only)",
                example = "true")
        boolean verified,

        @Schema(description = "When this seller first appeared on the marketplace. Null when there "
                + "is no trust record yet.", example = "2026-04-01T09:15:00Z", nullable = true)
        Instant since,

        @Schema(description = "Average rating across every review of every listing this merchant "
                + "owns, one decimal. Null when they have no reviews yet — never 0.0, which would "
                + "read as a terrible seller rather than an unrated one.",
                example = "5.0", nullable = true)
        Double ratingAvg,

        @Schema(description = "Total verified-purchase reviews across all this merchant's listings",
                example = "1")
        long reviewCount,

        @Schema(description = "How many of this merchant's listings are ACTIVE — the number a "
                + "shopper can browse. Excludes DRAFT and ARCHIVED.", example = "12")
        long activeListingCount
) {
}
