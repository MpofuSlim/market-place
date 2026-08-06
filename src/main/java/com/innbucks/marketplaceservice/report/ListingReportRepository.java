package com.innbucks.marketplaceservice.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ListingReportRepository extends JpaRepository<ListingReport, UUID> {

    /** One OPEN report per reporter per listing — the app-level check; the V7
     *  partial unique index is the backstop under a race. */
    boolean existsByListingIdAndReporterUuidAndStatus(UUID listingId, UUID reporterUuid,
                                                      ReportStatus status);

    /** Moderation queue page; callers order via the pageable (default
     *  oldest-first — a queue is worked FIFO). Served by the V7
     *  (status, created_at) index. */
    Page<ListingReport> findByStatus(ReportStatus status, Pageable pageable);
}
