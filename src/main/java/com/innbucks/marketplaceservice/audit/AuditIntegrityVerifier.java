package com.innbucks.marketplaceservice.audit;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Verifies the tamper-evidence HMAC on {@code audit_events} rows (OWASP A09).
 *
 * <p>{@link AuditService} seals every row with an HMAC over its immutable fields
 * (keyed by {@code marketplace.audit.hmac-secret}, which lives in config/env,
 * never in the DB). This component recomputes that HMAC and compares it to the
 * stored value, so a row silently altered by anyone with DB write access is
 * detectable — the forger can't produce a matching HMAC without the key.
 *
 * <p>Runs nightly (ShedLock-elected, so replicas never double-scan) over the
 * most recent {@code marketplace.audit.verify-limit} rows and exports
 * {@link MarketplaceMetrics#auditIntegrityBroken(long)} so the fleet Prometheus
 * can page on any nonzero result (the invariant is zero). Read-only and
 * idempotent.
 *
 * <p><b>Hash-chain.</b> The per-row HMAC only proves a row's content is intact —
 * it cannot detect a whole row being DELETED, REORDERED, or truncated from the
 * tail. So each row also carries a {@code chain_hmac} linking it to its
 * predecessor. This verifier additionally walks the window oldest-first and
 * recomputes each link; a mismatch means a neighbouring row was removed or
 * moved, and is exported as {@link MarketplaceMetrics#auditChainBroken(long)}
 * (ticket severity, WARN log — surviving content is intact and a break can also
 * be a benign secret rotation, so it warrants investigation rather than an
 * immediate page). The first chained row in the window is treated as an anchor:
 * its predecessor lies outside the scanned window, so its back-link can't be
 * checked here. Rows with a NULL {@code chain_hmac} are legacy by fleet
 * convention — never counted as a break.
 */
@Component
@Slf4j
public class AuditIntegrityVerifier {

    private final AuditEventRepository repository;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;

    /** How many of the newest rows to check per run. Bounds the scan cost on a
     *  table that grows with order volume; the alert catches tampering within
     *  this window, and an ops-run full sweep can widen it for an investigation. */
    private final int verifyLimit;

    public AuditIntegrityVerifier(AuditEventRepository repository,
                                  AuditService auditService,
                                  MarketplaceMetrics metrics,
                                  @Value("${marketplace.audit.verify-limit:5000}") int verifyLimit) {
        this.repository = repository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.verifyLimit = verifyLimit;
    }

    /**
     * Outcome of a verification pass. {@code tampered > 0} (content altered) or
     * {@code chainBroken > 0} (row deleted/reordered) is a security incident.
     */
    public record Result(int checked, int ok, int tampered, int legacy,
                         int chainOk, int chainBroken) {}

    @Scheduled(cron = "${marketplace.scheduler.audit-verify-cron}")
    @SchedulerLock(name = "auditIntegrityVerifier")
    public void scheduledVerify() {
        Result r = verifyRecent();
        if (r.tampered() > 0) {
            log.error("AUDIT_INTEGRITY_BROKEN checked={} ok={} TAMPERED={} legacy={} "
                    + "chainOk={} chainBroken={} — an audit_events row failed HMAC "
                    + "verification; investigate immediately",
                    r.checked(), r.ok(), r.tampered(), r.legacy(), r.chainOk(), r.chainBroken());
        } else if (r.chainBroken() > 0) {
            log.warn("AUDIT_CHAIN_BROKEN checked={} ok={} legacy={} chainOk={} CHAIN_BROKEN={} — "
                    + "an audit_events chain link failed to recompute; a row may have been "
                    + "deleted or reordered (or the hmac-secret rotated without a re-chain)",
                    r.checked(), r.ok(), r.legacy(), r.chainOk(), r.chainBroken());
        } else {
            log.info("Audit integrity verified: checked={} ok={} legacy={} chainOk={} "
                    + "(no tampering, chain intact)",
                    r.checked(), r.ok(), r.legacy(), r.chainOk());
        }
        if (r.tampered() > 0 || r.chainBroken() > 0) {
            // Pin the incident in the (still tamper-evident) trail itself: a new
            // sealed+chained row the attacker can't remove without extending the
            // very evidence it records. record() never throws.
            auditService.record(AuditEventType.AUDIT_VERIFICATION_FAILED, null, null,
                    Map.of("checked", r.checked(), "tampered", r.tampered(),
                           "chainBroken", r.chainBroken(), "legacy", r.legacy()));
        }
    }

    /**
     * Recompute + compare the HMAC on the most recent {@code verifyLimit} rows,
     * then walk their chain oldest-first. Emits the two
     * {@code marketplace.audit.*.broken} counters so alerts fire even if nobody
     * reads the logs. Never throws — a verifier that crashes is worse than one
     * that reports zero.
     */
    public Result verifyRecent() {
        int checked = 0, ok = 0, tampered = 0, legacy = 0, chainOk = 0, chainBroken = 0;
        try {
            List<AuditEvent> rows = repository.findAll(
                    PageRequest.of(0, verifyLimit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
            for (AuditEvent row : rows) {
                checked++;
                String stored = row.getRowHmac();
                if (stored == null || stored.isBlank()) {
                    legacy++;                       // unverifiable, not tampered (fleet convention;
                                                    // shouldn't occur here — row_hmac is NOT NULL)
                } else if (stored.equals(auditService.computeHmac(row))) {
                    ok++;
                } else {
                    tampered++;
                    log.error("AUDIT_ROW_TAMPERED id={} eventType={} createdAt={} — "
                            + "stored HMAC does not match recomputed",
                            row.getId(), row.getEventType(), row.getCreatedAt());
                }
            }
            int[] chain = verifyChain(rows);
            chainOk = chain[0];
            chainBroken = chain[1];
        } catch (RuntimeException ex) {
            log.error("Audit integrity verification pass failed to run: {}", ex.getMessage(), ex);
        }
        metrics.auditIntegrityBroken(tampered);
        metrics.auditChainBroken(chainBroken);
        return new Result(checked, ok, tampered, legacy, chainOk, chainBroken);
    }

    /**
     * Walk the hash-chain oldest-first and recompute each link (OWASP A09
     * deletion/reorder detection). Returns {@code [chainOk, chainBroken]}.
     *
     * <p>{@code rows} arrives newest-first (the DESC scan), so we reverse to
     * oldest-first. The first chained row in the window is an <em>anchor</em>: its
     * predecessor lies outside the scanned window, so its back-link can't be
     * checked and it is neither ok nor broken. Each subsequent row's stored
     * {@code chain_hmac} must equal {@code HMAC(prevChain, row.rowHmac)}; a
     * mismatch means a row between them was deleted or reordered. After a break we
     * re-anchor on the stored chain value so ONE deletion flags exactly once
     * rather than cascading. A legacy (null chain) row resets the anchor, since
     * the chain isn't continuous across it.
     */
    private int[] verifyChain(List<AuditEvent> rows) {
        List<AuditEvent> asc = new ArrayList<>(rows);
        Collections.reverse(asc);                   // oldest-first for the forward walk
        int chainOk = 0, chainBroken = 0;
        String prevChain = null;
        boolean anchored = false;
        for (AuditEvent row : asc) {
            String storedChain = row.getChainHmac();
            if (storedChain == null || storedChain.isBlank()) {
                anchored = false;                   // legacy row: chain not applicable, never a break
                prevChain = null;
                continue;
            }
            if (!anchored) {
                prevChain = storedChain;            // predecessor outside window: anchor only
                anchored = true;
                continue;
            }
            String expected = auditService.computeChainHmac(prevChain, row.getRowHmac());
            if (expected.equals(storedChain)) {
                chainOk++;
            } else {
                chainBroken++;
                log.warn("AUDIT_CHAIN_BROKEN id={} eventType={} createdAt={} — chain link "
                        + "does not recompute; a preceding audit row may have been deleted or reordered",
                        row.getId(), row.getEventType(), row.getCreatedAt());
            }
            prevChain = storedChain;                // re-anchor on stored so one break flags once
        }
        return new int[]{chainOk, chainBroken};
    }
}
