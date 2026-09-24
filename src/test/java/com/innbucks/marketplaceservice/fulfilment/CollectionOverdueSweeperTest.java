package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.notify.SellerAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Once per parcel, claim before send, one bad row never starves the rest. */
class CollectionOverdueSweeperTest {

    private OrderFulfilmentRepository repository;
    private SellerAlertService alerts;
    private CollectionOverdueSweeper sweeper;

    @BeforeEach
    void setUp() {
        repository = mock(OrderFulfilmentRepository.class);
        alerts = mock(SellerAlertService.class);
        sweeper = new CollectionOverdueSweeper(repository, alerts, 7);
    }

    private static OrderFulfilmentRepository.OverdueCollection row(UUID id, UUID merchant,
                                                                   String ref) {
        return new OrderFulfilmentRepository.OverdueCollection() {
            public UUID getFulfilmentId() { return id; }
            public UUID getMerchantId() { return merchant; }
            public String getOrderRef() { return ref; }
            public Instant getDispatchedAt() { return Instant.now().minus(Duration.ofDays(9)); }
        };
    }

    @Test
    @DisplayName("asks for collections set aside before the threshold, claims each, then alerts")
    void claimsThenAlerts() {
        UUID parcel = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        when(repository.findOverdueCollections(any(), anyInt()))
                .thenReturn(List.of(row(parcel, merchant, "MKT-4F9A1C22B7D3")));
        when(repository.claimOverdueCollectionAlert(eq(parcel), any())).thenReturn(1);

        Instant before = Instant.now();
        sweeper.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findOverdueCollections(cutoff.capture(),
                eq(CollectionOverdueSweeper.BATCH_LIMIT));
        assertThat(cutoff.getValue())
                .isBetween(before.minus(Duration.ofDays(7)).minusSeconds(1),
                        Instant.now().minus(Duration.ofDays(7)));
        var order = inOrder(repository, alerts);
        order.verify(repository).claimOverdueCollectionAlert(eq(parcel), any());
        order.verify(alerts).collectionOverdue(merchant, "MKT-4F9A1C22B7D3", parcel, 7);
    }

    @Test
    @DisplayName("a parcel collected (or claimed elsewhere) since the read is skipped")
    void lostClaimIsSkipped() {
        UUID parcel = UUID.randomUUID();
        when(repository.findOverdueCollections(any(), anyInt()))
                .thenReturn(List.of(row(parcel, UUID.randomUUID(), "MKT-1")));
        when(repository.claimOverdueCollectionAlert(eq(parcel), any())).thenReturn(0);

        sweeper.sweep();

        verify(alerts, never()).collectionOverdue(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("one failing row does not stop the others, and nothing escapes the sweep")
    void perRowIsolation() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        when(repository.findOverdueCollections(any(), anyInt())).thenReturn(List.of(
                row(bad, merchant, "MKT-BAD"), row(good, merchant, "MKT-GOOD")));
        doThrow(new IllegalStateException("db blip"))
                .when(repository).claimOverdueCollectionAlert(eq(bad), any());
        when(repository.claimOverdueCollectionAlert(eq(good), any())).thenReturn(1);

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();

        verify(alerts).collectionOverdue(merchant, "MKT-GOOD", good, 7);
        verify(alerts, never()).collectionOverdue(merchant, "MKT-BAD", bad, 7);
    }
}
