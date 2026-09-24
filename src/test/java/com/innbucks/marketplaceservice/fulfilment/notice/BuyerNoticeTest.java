package com.innbucks.marketplaceservice.fulfilment.notice;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        BuyerNoticeRecorder recorder = new BuyerNoticeRecorder(repository);
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
