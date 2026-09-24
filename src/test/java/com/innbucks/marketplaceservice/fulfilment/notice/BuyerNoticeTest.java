package com.innbucks.marketplaceservice.fulfilment.notice;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.notify.SellerAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyerNoticeTest {

    @Test
    @DisplayName("Every notification outcome maps to what the seller needs to know")
    void outcomeMapping() {
        assertThat(BuyerNoticeOutcome.fromMetricOutcome("sent")).isEqualTo(BuyerNoticeOutcome.SMS);
        assertThat(BuyerNoticeOutcome.fromMetricOutcome("fallback")).isEqualTo(BuyerNoticeOutcome.WHATSAPP);
        assertThat(BuyerNoticeOutcome.fromMetricOutcome("failed")).isEqualTo(BuyerNoticeOutcome.FAILED);
        assertThat(BuyerNoticeOutcome.fromMetricOutcome("disabled")).isEqualTo(BuyerNoticeOutcome.NOT_SENT);
        assertThat(BuyerNoticeOutcome.fromMetricOutcome("no_recipient")).isEqualTo(BuyerNoticeOutcome.NOT_SENT);
        assertThat(BuyerNoticeOutcome.fromMetricOutcome(null)).isEqualTo(BuyerNoticeOutcome.NOT_SENT);
    }

    @Test
    @DisplayName("The recorder writes by bulk update, and a database fault never escapes it")
    void recorderNeverThrows() {
        OrderFulfilmentRepository repository = mock(OrderFulfilmentRepository.class);
        BuyerNoticeRecorder recorder = new BuyerNoticeRecorder(repository,
                mock(SellerAlertService.class));
        UUID parcel = UUID.randomUUID();

        recorder.record(parcel, BuyerNoticeKind.DISPATCHED, BuyerNoticeOutcome.FAILED);
        verify(repository).recordBuyerNotice(eq(parcel), eq("DISPATCHED"), eq("FAILED"), any());

        when(repository.recordBuyerNotice(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> recorder.record(parcel, BuyerNoticeKind.CANCELLED,
                BuyerNoticeOutcome.SMS)).doesNotThrowAnyException();
        // No parcel id (an event from before V15): nothing to write, nothing thrown.
        assertThatCode(() -> recorder.record(null, BuyerNoticeKind.CANCELLED,
                BuyerNoticeOutcome.SMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A buyer we could not reach is the seller's to tell - alerted on FAILED only")
    void aFailedNoticeAlertsTheSeller() {
        OrderFulfilmentRepository repository = mock(OrderFulfilmentRepository.class);
        SellerAlertService alerts = mock(SellerAlertService.class);
        BuyerNoticeRecorder recorder = new BuyerNoticeRecorder(repository, alerts);
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        OrderFulfilment parcel = new OrderFulfilment();
        parcel.setId(parcelId);
        parcel.setMerchantId(merchantId);
        when(repository.findById(parcelId)).thenReturn(Optional.of(parcel));

        recorder.record(parcelId, "MKT-4F9A1C22B7D3", BuyerNoticeKind.DISPATCHED,
                BuyerNoticeOutcome.FAILED);
        verify(alerts).buyerNotReached(merchantId, "MKT-4F9A1C22B7D3", parcelId,
                "that it is on its way");

        // Delivered (or deliberately not sent) is not the seller's problem.
        recorder.record(parcelId, "MKT-4F9A1C22B7D3", BuyerNoticeKind.DISPATCHED,
                BuyerNoticeOutcome.SMS);
        recorder.record(parcelId, "MKT-4F9A1C22B7D3", BuyerNoticeKind.DISPATCHED,
                BuyerNoticeOutcome.WHATSAPP);
        recorder.record(parcelId, "MKT-4F9A1C22B7D3", BuyerNoticeKind.DISPATCHED,
                BuyerNoticeOutcome.NOT_SENT);
        // The legacy 3-arg form has no reference to put in an alert.
        recorder.record(parcelId, BuyerNoticeKind.DISPATCHED, BuyerNoticeOutcome.FAILED);
        verify(alerts).buyerNotReached(any(), anyString(), any(), anyString());

        // A lookup that blows up still never escapes.
        when(repository.findById(parcelId)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> recorder.record(parcelId, "MKT-4F9A1C22B7D3",
                BuyerNoticeKind.CANCELLED, BuyerNoticeOutcome.FAILED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Every notice kind names what the buyer missed")
    void everyKindHasWording() {
        for (BuyerNoticeKind kind : BuyerNoticeKind.values()) {
            assertThat(BuyerNoticeRecorder.whatWeFailedToSay(kind)).isNotBlank();
        }
    }

    @Test
    @DisplayName("The card shows nothing until a notice exists, and survives a value from a newer build")
    void viewIsAbsentUntilRecorded() {
        OrderFulfilment parcel = new OrderFulfilment();
        assertThat(BuyerNoticeView.of(parcel)).isNull();

        parcel.setBuyerNoticeKind("DISPATCHED");
        parcel.setBuyerNoticeOutcome("FAILED");
        parcel.setBuyerNoticeAt(Instant.parse("2026-09-24T09:20:04Z"));
        assertThat(BuyerNoticeView.of(parcel)).isEqualTo(new BuyerNoticeView(
                BuyerNoticeKind.DISPATCHED, BuyerNoticeOutcome.FAILED,
                Instant.parse("2026-09-24T09:20:04Z")));

        parcel.setBuyerNoticeOutcome("CARRIER_PIGEON");
        assertThat(BuyerNoticeView.of(parcel)).isNull();
    }
}
