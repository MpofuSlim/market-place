package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.config.MarketZone;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.dto.SettlementSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SUPER_ADMIN's "where is the money" with no merchant named: the whole
 * platform, never the old 400 (owner rule, 2026-09-30 — no read may scope the
 * platform owner out). The per-merchant queries are never touched.
 */
class PlatformSummaryTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");
    private static final UUID PAYABLE = UUID.randomUUID();
    private static final UUID UNPAYABLE = UUID.randomUUID();

    private MerchantSettlementRepository repository;
    private SellerService sellers;
    private SettlementQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(MerchantSettlementRepository.class);
        sellers = mock(SellerService.class);
        service = new SettlementQueryService(repository, mock(SettlementService.class), sellers,
                mock(SettlementViewAssembler.class), mock(MarketZone.class), mock(AuditService.class));

        MerchantSettlementRepository.StatusTotal held = mock(MerchantSettlementRepository.StatusTotal.class);
        when(held.getStatus()).thenReturn(SettlementStatus.HELD);
        when(held.getParcels()).thenReturn(7L);
        when(held.getNetCents()).thenReturn(42000L);
        when(repository.summarizePlatform()).thenReturn(List.of(held));
        when(repository.netClearingByPlatform(any())).thenReturn(1200L);
        when(repository.payoutRunsPlatform(any())).thenReturn(List.of());
    }

    private static MerchantSettlementRepository.PayoutRow owed(UUID merchantId) {
        MerchantSettlementRepository.PayoutRow row = mock(MerchantSettlementRepository.PayoutRow.class);
        when(row.getMerchantId()).thenReturn(merchantId);
        return row;
    }

    private static MarketplaceSeller seller(boolean withDestination) {
        MarketplaceSeller s = mock(MarketplaceSeller.class);
        when(s.hasPayoutDestination()).thenReturn(withDestination);
        return s;
    }

    @Test
    @DisplayName("No merchant named: every seller's totals, and no merchant id on the answer")
    void adminWithNoMerchantReadsThePlatform() {
        when(repository.payoutReport()).thenReturn(List.of());

        SettlementSummaryResponse out = service.summary(ADMIN, null);

        assertThat(out.merchantId()).isNull();
        assertThat(out.totals()).singleElement()
                .satisfies(line -> assertThat(line.netCents()).isEqualTo(42000L));
        assertThat(out.clearingNext7DaysCents()).isEqualTo(1200L);
        // Nobody is owed money, so nobody is left unpayable.
        assertThat(out.payoutDestinationConfigured()).isTrue();
        verify(repository, never()).summarize(any());
    }

    @Test
    @DisplayName("One seller owed money with no destination makes the platform flag false")
    void anUnpayableOwedSellerIsFlagged() {
        List<MerchantSettlementRepository.PayoutRow> rows = List.of(owed(PAYABLE), owed(UNPAYABLE));
        Map<UUID, MarketplaceSeller> byId = Map.of(PAYABLE, seller(true), UNPAYABLE, seller(false));
        when(repository.payoutReport()).thenReturn(rows);
        when(sellers.findAllByMerchantIds(any())).thenReturn(byId);

        assertThat(service.summary(ADMIN, null).payoutDestinationConfigured()).isFalse();
    }
}
