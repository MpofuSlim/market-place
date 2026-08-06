package com.innbucks.marketplaceservice.report;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.report.dto.ReportPageResponse;
import com.innbucks.marketplaceservice.report.dto.ReportRequest;
import com.innbucks.marketplaceservice.report.dto.ReportResolutionRequest;
import com.innbucks.marketplaceservice.report.dto.ReportResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Listing reports + the SUPER_ADMIN moderation queue (V7). Filing requires
 * authentication (spam control) but ANY role may file; one OPEN report per
 * (reporter, listing) — existsBy check with the V7 partial unique index as the
 * race backstop. Closing a report is SUPER_ADMIN-only (controller gate);
 * resolving may also deactivate the listing — always allowed, since only the
 * transition TO ACTIVE is publish-gated.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Same hard pagination cap as the public catalog. */
    static final int MAX_PAGE_SIZE = 50;

    /** The moderation queue is worked FIFO: oldest OPEN report first (unlike
     *  the newest-first buyer-facing lists — a queue that showed newest first
     *  would starve the oldest complaints). */
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Direction.ASC, "createdAt");

    private final ListingReportRepository reportRepository;
    private final ListingRepository listingRepository;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;

    // ------------------------------------------------------------------
    // Filing (any authenticated user)
    // ------------------------------------------------------------------

    @Transactional
    public ReportResponse report(AuthenticatedUser caller, UUID listingId, ReportRequest request) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> ApiException.notFound("listing_not_found", "Listing not found"));
        UUID reporterUuid = UUID.fromString(caller.uuid());
        if (reportRepository.existsByListingIdAndReporterUuidAndStatus(
                listingId, reporterUuid, ReportStatus.OPEN)) {
            throw ApiException.conflict("report_already_open",
                    "You already have an open report for this listing");
        }
        ListingReport report = ListingReport.builder()
                .id(UUID.randomUUID())
                .listingId(listingId)
                .reporterUuid(reporterUuid)
                .reason(request.reason())
                .detail(sanitizedOrNull(request.detail()))
                .status(ReportStatus.OPEN)
                .createdAt(Instant.now())
                .build();
        try {
            // Flushed HERE so losing the partial-unique-index race to a
            // concurrent duplicate surfaces as the same 409 — never a 500.
            reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("report_already_open",
                    "You already have an open report for this listing");
        }
        // Metadata carries the bounded reason enum, never the free-text detail
        // (unsanitizable-by-contract fields stay out of the sealed audit row).
        auditService.record(AuditEventType.LISTING_REPORTED, caller.uuid(), listingId.toString(),
                Map.of("reportId", report.getId().toString(),
                        "merchantId", listing.getMerchantId().toString(),
                        "reason", request.reason().name()));
        metrics.reportCreated(request.reason().name());
        return ReportResponse.from(report, listing);
    }

    // ------------------------------------------------------------------
    // Moderation (SUPER_ADMIN — gated in the controller)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReportPageResponse queue(ReportStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE), OLDEST_FIRST);
        Page<ListingReport> reports = reportRepository.findByStatus(status, pageable);
        // One grouped listing read for the whole page (no N+1) — the
        // ListingViewAssembler discipline, without the image metadata this
        // surface doesn't need.
        List<UUID> listingIds = reports.getContent().stream()
                .map(ListingReport::getListingId).distinct().toList();
        Map<UUID, Listing> listings = listingIds.isEmpty() ? Map.of()
                : listingRepository.findAllById(listingIds).stream()
                        .collect(Collectors.toMap(Listing::getId, Function.identity()));
        return ReportPageResponse.from(reports.map(
                r -> ReportResponse.from(r, listings.get(r.getListingId()))));
    }

    /**
     * Closes an OPEN report. RESOLVE may additionally deactivate the reported
     * listing ({@code deactivateListing=true}) — the status flip goes through
     * the same semantics as the merchant status path (deactivation is never
     * publish-gated) and is audited as LISTING_STATUS_CHANGED alongside the
     * LISTING_REPORT_RESOLVED row. Non-OPEN reports are terminal: 409
     * {@code report_not_open}, never re-closed.
     */
    @Transactional
    public ReportResponse resolve(AuthenticatedUser caller, UUID reportId,
                                  ReportResolutionRequest request) {
        ListingReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("report_not_found", "Report not found"));
        if (report.getStatus() != ReportStatus.OPEN) {
            throw ApiException.conflict("report_not_open",
                    "Report " + reportId + " is already " + report.getStatus());
        }
        boolean deactivate = request.deactivate();
        if (deactivate && request.action() != ReportResolutionRequest.Action.RESOLVE) {
            // Deactivating on DISMISS would contradict the dismissal — refuse
            // loudly rather than silently ignoring the flag.
            throw ApiException.badRequest("deactivate_requires_resolve",
                    "deactivateListing is only valid with action RESOLVE");
        }
        report.setStatus(request.action() == ReportResolutionRequest.Action.RESOLVE
                ? ReportStatus.RESOLVED : ReportStatus.DISMISSED);
        report.setResolvedBy(UUID.fromString(caller.uuid()));
        report.setResolutionNote(sanitizedOrNull(request.resolutionNote()));
        report.setResolvedAt(Instant.now());
        reportRepository.save(report);

        Listing listing = listingRepository.findById(report.getListingId()).orElse(null);
        boolean deactivated = false;
        if (deactivate && listing != null && listing.getStatus() != ListingStatus.INACTIVE) {
            ListingStatus from = listing.getStatus();
            listing.setStatus(ListingStatus.INACTIVE);
            listing.setUpdatedAt(Instant.now());
            listingRepository.save(listing);
            deactivated = true;
            auditService.record(AuditEventType.LISTING_STATUS_CHANGED, caller.uuid(),
                    listing.getId().toString(),
                    Map.of("merchantId", listing.getMerchantId().toString(),
                            "from", from.name(),
                            "to", ListingStatus.INACTIVE.name(),
                            "via", "moderation",
                            "reportId", report.getId().toString()));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("listingId", report.getListingId().toString());
        metadata.put("action", request.action().name());
        metadata.put("deactivatedListing", deactivated);
        auditService.record(AuditEventType.LISTING_REPORT_RESOLVED, caller.uuid(),
                report.getId().toString(), metadata);
        metrics.reportResolved(request.action().name());
        return ReportResponse.from(report, listing);
    }

    private static String sanitizedOrNull(String raw) {
        String sanitized = TextSanitizer.sanitize(raw);
        return (sanitized == null || sanitized.isBlank()) ? null : sanitized;
    }
}
