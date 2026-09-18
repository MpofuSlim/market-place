package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stale-escrow sweep reports and does NOT decide. Pins both halves: the
 * gauge always reflects the last scan (so an alert can fire on a series that
 * exists from boot), and nothing in the sweep ever moves a settlement — the
 * transfer, in either direction, is an operator's to make.
 */
class StaleEscrowSweeperTest {

    private SettlementService settlementService;
    private SimpleMeterRegistry registry;
    private StaleEscrowSweeper sweeper;

    @BeforeEach
    void setUp() {
        settlementService = mock(SettlementService.class);
        registry = new SimpleMeterRegistry();
        sweeper = new StaleEscrowSweeper(settlementService, new MarketplaceMetrics(registry));
    }

    private double gauge() {
        var gauge = registry.find("marketplace.settlements.stale").gauge();
        return gauge == null ? -1 : gauge.value();
    }

    private static MerchantSettlement held(long netCents, Instant createdAt) {
        return MerchantSettlement.builder()
                .id(UUID.randomUUID()).orderId(UUID.randomUUID()).fulfilmentId(UUID.randomUUID())
                .merchantId(UUID.randomUUID()).status(SettlementStatus.HELD)
                .grossCents(netCents).commissionCents(0).netCents(netCents).currency("USD")
                .createdAt(createdAt).updatedAt(createdAt).version(0L).build();
    }

    @Test
    @DisplayName("The gauge carries the count of money no timer can release")
    void gaugeReportsWhatTheScanFound() {
        when(settlementService.staleHeld(StaleEscrowSweeper.SCAN_LIMIT)).thenReturn(List.of(
                held(4798, Instant.now().minusSeconds(30 * 86400)),
                held(2599, Instant.now().minusSeconds(20 * 86400))));

        sweeper.sweep();

        assertThat(gauge()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("A clean sweep resets the gauge to zero — a stale reading is worse than none")
    void aCleanSweepResetsTheGauge() {
        when(settlementService.staleHeld(StaleEscrowSweeper.SCAN_LIMIT)).thenReturn(List.of(
                held(4798, Instant.now().minusSeconds(30 * 86400))));
        sweeper.sweep();
        assertThat(gauge()).isEqualTo(1.0);

        // The operator refunded or chased it; the next run must say so, or the
        // alert stays lit against a backlog that has already been worked.
        when(settlementService.staleHeld(StaleEscrowSweeper.SCAN_LIMIT)).thenReturn(List.of());
        sweeper.sweep();

        assertThat(gauge()).isZero();
    }

    @Test
    @DisplayName("The scan is bounded — one pathological backlog cannot pull an unbounded set")
    void theScanIsBounded() {
        when(settlementService.staleHeld(StaleEscrowSweeper.SCAN_LIMIT)).thenReturn(List.of());

        sweeper.sweep();

        verify(settlementService).staleHeld(StaleEscrowSweeper.SCAN_LIMIT);
        assertThat(StaleEscrowSweeper.SCAN_LIMIT).isPositive();
    }

    @Test
    @DisplayName("It reports and does not decide: not one settlement is moved")
    void itNeverMovesMoney() {
        MerchantSettlement stale = held(4798, Instant.now().minusSeconds(30 * 86400));
        when(settlementService.staleHeld(StaleEscrowSweeper.SCAN_LIMIT)).thenReturn(List.of(stale));

        sweeper.sweep();

        // Auto-releasing would pay a seller who never delivered; auto-refunding
        // would punish one who is merely slow. The sweep's whole output is the
        // gauge above and a log line.
        assertThat(stale.getStatus()).isEqualTo(SettlementStatus.HELD);
        assertThat(stale.getReleasedAt()).isNull();
        assertThat(stale.getRefundDueAt()).isNull();
    }
}
