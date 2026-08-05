package com.innbucks.marketplaceservice.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AuditIntegrityVerifier}'s two detections (OWASP A09): content
 * tamper (row_hmac recompute mismatch → page metric) and chain break (a
 * deleted/reordered predecessor → ticket metric), plus the legacy NULL-chain
 * convention that must never flag. Rows are sealed with a REAL
 * {@link AuditService} (mocked persistence — only the HMAC math is used) and
 * served from a mocked repository; metrics land in a fresh
 * {@link SimpleMeterRegistry} per test.
 */
class AuditIntegrityVerifierTest {

    private static final String SECRET =
            "audit-hmac-unit-secret-0123456789abcdefghijklmnop";

    private SimpleMeterRegistry registry;
    private AuditEventRepository repository;
    private AuditService auditService;
    private AuditIntegrityVerifier verifier;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        repository = mock(AuditEventRepository.class);
        auditService = new AuditService(mock(AuditEventRepository.class),
                mock(AuditChainHeadRepository.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class), SECRET);
        verifier = new AuditIntegrityVerifier(repository, auditService,
                new MarketplaceMetrics(registry), 5000);
    }

    /** Builds {@code n} correctly sealed + chained rows, oldest-first. */
    private List<AuditEvent> intactChain(int n) {
        List<AuditEvent> rows = new ArrayList<>();
        String prevChain = null;
        for (int i = 1; i <= n; i++) {
            AuditEvent e = AuditEvent.builder()
                    .id((long) i)
                    .eventType(AuditEventType.ORDER_CREATED.name())
                    .actorUuid("actor-" + i)
                    .targetId("target-" + i)
                    .metadata("{\"seq\":" + i + "}")
                    .createdAt(Instant.parse("2026-08-05T00:00:00Z").plusSeconds(i))
                    .build();
            e.setRowHmac(auditService.computeHmac(e));
            e.setChainHmac(auditService.computeChainHmac(prevChain, e.getRowHmac()));
            prevChain = e.getChainHmac();
            rows.add(e);
        }
        return rows;
    }

    /** The repository serves the window newest-first (DESC by id). */
    private void repositoryReturns(List<AuditEvent> oldestFirst) {
        List<AuditEvent> newestFirst = new ArrayList<>(oldestFirst);
        Collections.reverse(newestFirst);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(newestFirst));
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void intactWindowReportsNoTamperAndNoChainBreaks() {
        repositoryReturns(intactChain(4));

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertThat(r.checked()).isEqualTo(4);
        assertThat(r.ok()).isEqualTo(4);
        assertThat(r.tampered()).isZero();
        assertThat(r.legacy()).isZero();
        // Oldest row anchors the window (predecessor outside it), the other
        // three links all recompute.
        assertThat(r.chainOk()).isEqualTo(3);
        assertThat(r.chainBroken()).isZero();
        assertThat(counter("marketplace.audit.integrity.broken")).isZero();
        assertThat(counter("marketplace.audit.chain.broken")).isZero();
    }

    @Test
    void tamperedRowContentIsFlaggedAndPagesTheIntegrityMetric() {
        List<AuditEvent> rows = intactChain(4);
        // Post-write content edit: the stored row_hmac no longer recomputes.
        rows.get(2).setMetadata("{\"seq\":3,\"amountCents\":999999}");
        repositoryReturns(rows);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertThat(r.tampered()).isEqualTo(1);
        assertThat(r.ok()).isEqualTo(3);
        // The chain links still recompute (they bind the STORED row_hmac values)
        // — content tamper and deletion are deliberately separate signals.
        assertThat(r.chainBroken()).isZero();
        assertThat(counter("marketplace.audit.integrity.broken")).isEqualTo(1.0);
        assertThat(counter("marketplace.audit.chain.broken")).isZero();
    }

    @Test
    void deletedRowBreaksTheChainExactlyOnce() {
        List<AuditEvent> rows = new ArrayList<>(intactChain(4));
        rows.remove(1);                        // delete row 2 of 1..4
        repositoryReturns(rows);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        // Surviving rows' content is intact...
        assertThat(r.tampered()).isZero();
        assertThat(r.ok()).isEqualTo(3);
        // ...but row 3's back-link no longer recomputes. Re-anchoring after the
        // break means ONE deletion flags exactly once (row 4 checks clean).
        assertThat(r.chainBroken()).isEqualTo(1);
        assertThat(r.chainOk()).isEqualTo(1);
        assertThat(counter("marketplace.audit.chain.broken")).isEqualTo(1.0);
        assertThat(counter("marketplace.audit.integrity.broken")).isZero();
    }

    @Test
    void swappedNeighbourRowsBreakTheChain() {
        List<AuditEvent> rows = new ArrayList<>(intactChain(4));
        Collections.swap(rows, 1, 2);          // reorder rows 2 and 3
        repositoryReturns(rows);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertThat(r.tampered()).isZero();
        assertThat(r.chainBroken()).isGreaterThan(0);
    }

    @Test
    void legacyNullChainRowsAreNeverFlaggedAsBreaks() {
        // Fleet convention: rows written before chaining have chain_hmac NULL —
        // unverifiable, never a break. The next chained row re-anchors.
        List<AuditEvent> rows = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            AuditEvent legacy = AuditEvent.builder()
                    .id((long) i)
                    .eventType(AuditEventType.LISTING_CREATED.name())
                    .createdAt(Instant.parse("2026-08-01T00:00:00Z").plusSeconds(i))
                    .build();
            legacy.setRowHmac(auditService.computeHmac(legacy));
            legacy.setChainHmac(null);
            rows.add(legacy);
        }
        List<AuditEvent> chained = intactChain(2);
        chained.get(0).setId(3L);
        chained.get(1).setId(4L);
        rows.addAll(chained);
        repositoryReturns(rows);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertThat(r.checked()).isEqualTo(4);
        assertThat(r.ok()).isEqualTo(4);       // legacy rows still content-verify
        assertThat(r.chainBroken()).isZero();
        assertThat(r.chainOk()).isEqualTo(1);  // row 3 re-anchors, row 4 verifies
        assertThat(counter("marketplace.audit.chain.broken")).isZero();
    }

    @Test
    void blankRowHmacCountsAsLegacyNotTampered() {
        List<AuditEvent> rows = intactChain(2);
        rows.get(0).setRowHmac("");
        rows.get(0).setChainHmac(null);
        repositoryReturns(rows);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertThat(r.legacy()).isEqualTo(1);
        assertThat(r.tampered()).isZero();
        assertThat(counter("marketplace.audit.integrity.broken")).isZero();
    }

    @Test
    void verifierNeverThrowsWhenTheScanFails() {
        when(repository.findAll(any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> {
            AuditIntegrityVerifier.Result r = verifier.verifyRecent();
            assertThat(r.checked()).isZero();
            assertThat(r.tampered()).isZero();
            assertThat(r.chainBroken()).isZero();
        }).doesNotThrowAnyException();
    }
}
