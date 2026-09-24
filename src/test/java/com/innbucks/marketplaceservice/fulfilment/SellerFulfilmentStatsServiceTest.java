package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository.DispatchTiming;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository.ParcelCounts;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentStatsResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.SellerFulfilmentStats;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the honesty rules the trust figures live by: computed only, silent
 * below the sample floor, and rounded AGAINST the seller — a stat that
 * flatters a seller once is a stat shoppers stop believing everywhere.
 */
class SellerFulfilmentStatsServiceTest {

    private static final int MIN_SAMPLE = 5;
    private static final UUID MERCHANT = UUID.randomUUID();

    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
            MERCHANT.toString(), null, null, "ZW");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

    private OrderFulfilmentRepository repository;
    private SellerFulfilmentStatsService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderFulfilmentRepository.class);
        service = new SellerFulfilmentStatsService(repository, MIN_SAMPLE, 7);
    }

    private void counts(long delivered, long buyerConfirmed, long awaiting, long inTransit) {
        ParcelCounts counts = mock(ParcelCounts.class);
        when(counts.getDelivered()).thenReturn(delivered);
        when(counts.getBuyerConfirmed()).thenReturn(buyerConfirmed);
        when(counts.getAwaitingDispatch()).thenReturn(awaiting);
        when(counts.getInTransit()).thenReturn(inTransit);
        when(repository.countParcels(MERCHANT)).thenReturn(counts);
        openCounts(inTransit, 0, 0);
    }

    private void openCounts(long onTheWay, long readyToCollect, long overdue) {
        OrderFulfilmentRepository.OpenParcelCounts open =
                mock(OrderFulfilmentRepository.OpenParcelCounts.class);
        when(open.getOnTheWay()).thenReturn(onTheWay);
        when(open.getReadyToCollect()).thenReturn(readyToCollect);
        when(open.getReadyToCollectOverdue()).thenReturn(overdue);
        when(repository.countOpenParcels(eq(MERCHANT), any())).thenReturn(open);
    }

    private void timing(Double medianSeconds, long sample) {
        DispatchTiming timing = mock(DispatchTiming.class);
        when(timing.getMedianSeconds()).thenReturn(medianSeconds);
        when(timing.getSample()).thenReturn(sample);
        when(repository.dispatchTiming(MERCHANT)).thenReturn(timing);
    }

    // ------------------------------------------------------------------
    // The whole block
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No completed parcel = NO block at all — a new seller is not a failing one")
    void noHistoryMeansNoBlock() {
        counts(0, 0, 2, 1);

        assertThat(service.publicStats(MERCHANT)).isNull();
        // Nothing further is computed for a seller with nothing to compute from.
        verify(repository, never()).dispatchTiming(any());
    }

    @Test
    @DisplayName("Below the sample floor, completedOrders shows but both figures stay null")
    void smallSamplesShowTheCountAndNothingElse() {
        counts(3, 3, 0, 0);
        timing(3600.0, 3);

        SellerFulfilmentStats stats = service.publicStats(MERCHANT);

        // "3 orders completed" is a fact; "100% confirmed" over 3 is noise
        // wearing a percentage.
        assertThat(stats.completedOrders()).isEqualTo(3);
        assertThat(stats.medianDispatchHours()).isNull();
        assertThat(stats.buyerConfirmedPercent()).isNull();
    }

    // ------------------------------------------------------------------
    // The figures
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Median dispatch rounds UP — the platform understates speed, never flatters")
    void medianRoundsUp() {
        counts(10, 9, 0, 0);
        timing(19.5 * 3600, 10); // 19.5h

        assertThat(service.publicStats(MERCHANT).medianDispatchHours()).isEqualTo(20);
    }

    @Test
    @DisplayName("A same-hour dispatch reads 1, never the absurd 0")
    void medianFloorsAtOneHour() {
        counts(10, 9, 0, 0);
        timing(600.0, 10); // 10 minutes

        assertThat(service.publicStats(MERCHANT).medianDispatchHours()).isEqualTo(1);
    }

    @Test
    @DisplayName("A seller who only hands over in person has NO dispatch figure, not a fake one")
    void handDeliveredOnlySellerHasNoDispatchFigure() {
        // Every parcel went PREPARING -> DELIVERED; nothing was ever
        // dispatched, so the timing sample is empty.
        counts(10, 10, 0, 0);
        timing(null, 0);

        SellerFulfilmentStats stats = service.publicStats(MERCHANT);

        assertThat(stats.medianDispatchHours()).isNull();
        assertThat(stats.buyerConfirmedPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("Buyer-confirmed percent is a plain rounded integer of BUYER-closed parcels")
    void buyerConfirmedPercent() {
        counts(24, 23, 0, 0); // 95.83 -> 96
        timing(3600.0, 24);

        assertThat(service.publicStats(MERCHANT).buyerConfirmedPercent()).isEqualTo(96);
    }

    // ------------------------------------------------------------------
    // The seller's own view
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The seller's own view carries the SAME public block plus their live queue")
    void merchantViewWrapsThePublicFigures() {
        counts(10, 9, 3, 5);
        timing(7200.0, 10);

        MerchantFulfilmentStatsResponse view = service.merchantStats(SELLER, null);

        assertThat(view.publicStats().completedOrders()).isEqualTo(10);
        assertThat(view.publicStats().medianDispatchHours()).isEqualTo(2);
        assertThat(view.awaitingDispatch()).isEqualTo(3);
        assertThat(view.inTransit()).isEqualTo(5);
        assertThat(view.completedOrders()).isEqualTo(10);
    }

    @Test
    @DisplayName("Open parcels split into on-the-way and ready-to-collect, with the overdue ones counted")
    void openParcelsAreSplitByMethod() {
        counts(10, 9, 0, 5);
        timing(7200.0, 10);
        openCounts(3, 2, 1);

        MerchantFulfilmentStatsResponse view = service.merchantStats(SELLER, null);

        assertThat(view.onTheWay()).isEqualTo(3);
        assertThat(view.readyToCollect()).isEqualTo(2);
        assertThat(view.readyToCollectOverdue()).isEqualTo(1);
        assertThat(view.collectionOverdueDays()).isEqualTo(7);
        // Overdue = set aside before (now - 7 days).
        org.mockito.ArgumentCaptor<java.time.Instant> cutoff =
                org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(repository).countOpenParcels(eq(MERCHANT), cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(
                java.time.Instant.now().minus(java.time.Duration.ofDays(7)).minusSeconds(5),
                java.time.Instant.now().minus(java.time.Duration.ofDays(7)).plusSeconds(1));
    }

    @Test
    @DisplayName("A merchant cannot widen their scope with the merchantId parameter")
    void merchantFilterIsIgnoredForSellers() {
        counts(10, 9, 0, 0);
        timing(3600.0, 10);

        // Asking for someone else's id still answers with THEIR OWN stats.
        service.merchantStats(SELLER, UUID.randomUUID());

        verify(repository).countParcels(MERCHANT);
    }

    @Test
    @DisplayName("SUPER_ADMIN must NAME a merchant — an admin token has no scope to default to")
    void adminMustNameAMerchant() {
        assertThatThrownBy(() -> service.merchantStats(ADMIN, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("merchant_id_required");
    }

    @Test
    @DisplayName("A merchant token with no merchant scope is a 403, not a 500")
    void scopelessMerchantIsForbidden() {
        AuthenticatedUser scopeless = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), null, null, null, "ZW");

        assertThatThrownBy(() -> service.merchantStats(scopeless, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("merchant_scope_missing");
    }
}
