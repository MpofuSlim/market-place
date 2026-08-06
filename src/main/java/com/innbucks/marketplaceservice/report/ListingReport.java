package com.innbucks.marketplaceservice.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's report of a listing (V7 {@code listing_report} table), feeding the
 * SUPER_ADMIN moderation queue. Reporting requires authentication (spam
 * control) but any role may file; the V7 partial unique index allows ONE OPEN
 * report per (reporter, listing) while keeping closed history.
 *
 * <p>Manually-assigned id, no {@code @Version} (the MarketOrderItem stance):
 * created once, closed once by a single moderation role — a lost-update race
 * on resolution is prevented by the OPEN-status check running in the same
 * transaction as the close.
 */
@Entity
@Table(name = "listing_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingReport {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "reporter_uuid", nullable = false)
    private UUID reporterUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReportReason reason;

    /** Optional free text, jsoup-sanitized before storage. */
    @Column(name = "detail", length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    /** The SUPER_ADMIN who closed the report. */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
