package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.fulfilment.ParcelUnfulfilled;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeKind;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeOutcome;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The unfulfilled-parcel listener's routing + guard rails. Same shape as the
 * order-paid listener's test and for the same reason: this runs AFTER_COMMIT,
 * so an exception escaping it would reach the caller of {@code commit()} and
 * make a dead SMS gateway look like a failed decline — leaving the parcel open
 * again, which is the exact state V12 exists to close.
 */
class ParcelUnfulfilledNotificationListenerTest {

    private static final String REF = "MKT-4F2A9C1B77D0";

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private SimpleMeterRegistry registry;
    private BuyerNoticeRecorder recorder;
    private ParcelUnfulfilledNotificationListener listener;

    private final ParcelUnfulfilled event = new ParcelUnfulfilled(UUID.randomUUID(), REF,
            "+263771234567", "out of stock", 1550, "USD", false);

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        registry = new SimpleMeterRegistry();
        recorder = mock(BuyerNoticeRecorder.class);
        listener = new ParcelUnfulfilledNotificationListener(sms, whatsApp,
                new MarketplaceMetrics(registry), recorder);
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "parcel_unfulfilled").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("SMS is the primary channel, carrying the queued refund amount")
    void smsIsThePrimaryChannel() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelUnfulfilled(event);

        verify(sms).sendSms(eq("+263771234567"),
                eq("Sorry - a seller cannot supply part of your InnBucks Marketplace order "
                        + "MKT-4F2A9C1B77D0. Reason - out of stock. A refund of USD 15.50 is "
                        + "being arranged. Ref MKT-4F2A9C1B77D0"),
                eq(REF));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(outcome("sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("WhatsApp picks up only when SMS actually fails — same message")
    void whatsAppIsTheFallback() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new NotificationDeliveryException("gateway 500"))
                .when(sms).sendSms(any(), any(), any());

        listener.onParcelUnfulfilled(event);

        verify(whatsApp).sendCustomNotification(eq("+263771234567"),
                contains("A refund of USD 15.50 is being arranged"));
        assertThat(outcome("fallback")).isEqualTo(1.0);
        assertThat(outcome("sent")).isZero();
    }

    @Test
    @DisplayName("No channel configured: outcome=disabled, and not one wire attempt")
    void noChannelMeansDisabled() {
        listener.onParcelUnfulfilled(event);

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(outcome("disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Every channel down: counted failed, and NOTHING escapes the listener")
    void everyChannelDownIsCountedNeverThrown() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(any(), any(), any());
        doThrow(new NotificationDeliveryException("whatsapp down"))
                .when(whatsApp).sendCustomNotification(any(), any());

        // The decline has already committed. A throw here would surface as a
        // failed decline to the seller, who would try again and find the
        // parcel already closed.
        assertThatCode(() -> listener.onParcelUnfulfilled(event)).doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Nothing queued for refund: the copy promises none")
    void promisesNoRefundWhenNoneWasQueued() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelUnfulfilled(new ParcelUnfulfilled(event.orderId(), REF,
                event.buyerMsisdn(), "out of stock", 0, "USD", false));

        verify(sms).sendSms(eq("+263771234567"),
                contains("Our support team will be in touch."), eq(REF));
    }

    @Test
    @DisplayName("A collection the buyer never came for is told as not picked up, not as out of stock")
    void aNoShowIsToldAsSuch() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelUnfulfilled(new ParcelUnfulfilled(event.orderId(), REF,
                event.buyerMsisdn(), "not collected within 5 days", 1550, "USD", true));

        verify(sms).sendSms(eq("+263771234567"),
                eq("A collection from your InnBucks Marketplace order MKT-4F2A9C1B77D0 was not "
                        + "picked up, so the seller has cancelled it. Reason - not collected "
                        + "within 5 days. A refund of USD 15.50 is being arranged. Ref "
                        + "MKT-4F2A9C1B77D0"),
                eq(REF));
    }

    @Test
    @DisplayName("The outcome is remembered on the parcel, so the seller can see the buyer was not told")
    void outcomeIsRecordedOnTheParcel() {
        UUID parcel = UUID.randomUUID();
        when(sms.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        listener.onParcelUnfulfilled(new ParcelUnfulfilled(event.orderId(), REF, "+263771234567",
                "out of stock", 1550, "USD", false, parcel));

        verify(recorder).record(parcel, BuyerNoticeKind.CANCELLED, BuyerNoticeOutcome.FAILED);
    }
}
