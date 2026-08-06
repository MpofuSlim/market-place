package com.innbucks.marketplaceservice.report.dto;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.report.ListingReport;
import com.innbucks.marketplaceservice.report.ReportReason;
import com.innbucks.marketplaceservice.report.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A report row on the moderation surface (and the POST acknowledgement).
 * Carries a small live summary of the reported listing (title/merchant/
 * current status) so the queue is triageable without a second read per row.
 */
@Schema(description = "A listing report with a live summary of the reported listing")
public record ReportResponse(

        @Schema(description = "Report id", example = "8c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f")
        UUID id,

        @Schema(description = "Reported listing", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
        UUID listingId,

        @Schema(description = "Reported listing's title (live, not a snapshot)",
                example = "Wireless Bluetooth Speaker", nullable = true)
        String listingTitle,

        @Schema(description = "Reported listing's owning merchant",
                example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54", nullable = true)
        UUID merchantId,

        @Schema(description = "Reported listing's CURRENT status (moderation may have already "
                + "deactivated it)", example = "ACTIVE", nullable = true)
        ListingStatus listingStatus,

        @Schema(description = "Who filed the report", example = "6f9619ff-8b86-4011-b42d-00c04fc964ff")
        UUID reporterUuid,

        @Schema(example = "COUNTERFEIT")
        ReportReason reason,

        @Schema(description = "Sanitized reporter detail; null when none was given",
                example = "Logo is fake — the brand does not make this model.", nullable = true)
        String detail,

        @Schema(example = "OPEN")
        ReportStatus status,

        @Schema(description = "SUPER_ADMIN who closed the report; null while OPEN",
                example = "0a1b2c3d-4e5f-4a6b-8c9d-e0f1a2b3c4d5", nullable = true)
        UUID resolvedBy,

        @Schema(description = "Sanitized moderator note; null while OPEN or when none was given",
                example = "Confirmed counterfeit; listing deactivated.", nullable = true)
        String resolutionNote,

        @Schema(description = "UTC instant", example = "2026-08-06T12:00:00Z")
        Instant createdAt,

        @Schema(description = "UTC instant; null while OPEN", example = "2026-08-06T14:30:00Z",
                nullable = true)
        Instant resolvedAt
) {

    /** @param listing the reported listing, or null if somehow absent (the FK
     *                 makes that impossible in practice — belt only). */
    public static ReportResponse from(ListingReport report, Listing listing) {
        return new ReportResponse(
                report.getId(),
                report.getListingId(),
                listing == null ? null : listing.getTitle(),
                listing == null ? null : listing.getMerchantId(),
                listing == null ? null : listing.getStatus(),
                report.getReporterUuid(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getResolvedBy(),
                report.getResolutionNote(),
                report.getCreatedAt(),
                report.getResolvedAt());
    }
}
