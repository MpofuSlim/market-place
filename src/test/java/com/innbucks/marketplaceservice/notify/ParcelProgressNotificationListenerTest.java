package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.ParcelProgressed;
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
 * The parcel-update listener's routing + guard rails. It runs AFTER_COMMIT, so
 * an exception escaping it would reach the caller of {@code commit()} and make
 * a dead SMS gateway look like a failed dispatch to a seller whose parcel had
 * in fact moved.
 */
class ParcelProgressNotificationListenerTest {

    private static final String REF = "MKT-4F2A9C1B77D0";
    private static final String BUYER = "+263771234567";

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private MarketplaceNotificationProperties properties;
    private SimpleMeterRegistry registry;
    private BuyerNoticeRecorder recorder;
    private ParcelProgressNotificationListener listener;

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        properties = new MarketplaceNotificationProperties();
        registry = new SimpleMeterRegistry();
        recorder = mock(BuyerNoticeRecorder.class);
        listener = new ParcelProgressNotificationListener(sms, whatsApp, properties,
                new MarketplaceMetrics(registry), 7, recorder);
    }

    private static ParcelProgressed event(DeliveryMethod method, FulfilmentStatus status) {
        return new ParcelProgressed(UUID.randomUUID(), REF, BUYER, method, status, false);
    }

    private double outcome(String type, String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", type).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("A seller's 'delivered' reaches the buyer by SMS, naming the dispute window")
    void sellerCloseIsSentWithTheWindow() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelProgressed(event(DeliveryMethod.DELIVERY, FulfilmentStatus.DELIVERED));

        verify(sms).sendSms(eq(BUYER),
                eq("The seller has marked your InnBucks Marketplace order MKT-4F2A9C1B77D0 as "
                        + "delivered. Not received it - report it in the app within 7 days. "
                        + "Ref MKT-4F2A9C1B77D0"),
                eq(REF));
        assertThat(outcome("parcel_delivered", "sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A collection set aside reaches the buyer as 'ready to collect', pointing at the code")
    void collectionReadyIsSent() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelProgressed(event(DeliveryMethod.COLLECTION, FulfilmentStatus.DISPATCHED));

        verify(sms).sendSms(eq(BUYER), contains("is ready to collect. Get your collection code"),
                eq(REF));
        assertThat(outcome("parcel_dispatched", "sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("WhatsApp picks up only when SMS actually fails — same message")
    void whatsAppIsTheFallback() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new NotificationDeliveryException("gateway 500"))
                .when(sms).sendSms(any(), any(), any());

        listener.onParcelProgressed(event(DeliveryMethod.DELIVERY, FulfilmentStatus.DELIVERED));

        verify(whatsApp).sendCustomNotification(eq(BUYER),
                contains("report it in the app within 7 days"));
        assertThat(outcome("parcel_delivered", "fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Switched off: outcome=disabled, and not one wire attempt")
    void switchedOffSendsNothing() {
        properties.getParcelUpdates().setEnabled(false);
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelProgressed(event(DeliveryMethod.DELIVERY, FulfilmentStatus.DISPATCHED));

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        assertThat(outcome("parcel_dispatched", "disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("No buyer number: counted, nothing sent, nothing thrown")
    void noBuyerNumberIsCounted() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelProgressed(new ParcelProgressed(UUID.randomUUID(), REF, " ",
                DeliveryMethod.DELIVERY, FulfilmentStatus.DISPATCHED, false));

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        assertThat(outcome("parcel_dispatched", "no_recipient")).isEqualTo(1.0);
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

        assertThatCode(() -> listener.onParcelProgressed(
                event(DeliveryMethod.DELIVERY, FulfilmentStatus.DELIVERED)))
                .doesNotThrowAnyException();
        assertThat(outcome("parcel_delivered", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Each outcome is remembered on the parcel under the right kind")
    void outcomesAreRecordedOnTheParcel() {
        UUID parcel = UUID.randomUUID();
        when(sms.isConfigured()).thenReturn(true);

        listener.onParcelProgressed(new ParcelProgressed(UUID.randomUUID(), REF, BUYER,
                DeliveryMethod.COLLECTION, FulfilmentStatus.DISPATCHED, false, parcel));
        verify(recorder).record(parcel, BuyerNoticeKind.READY_TO_COLLECT, BuyerNoticeOutcome.SMS);

        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("down")).when(sms).sendSms(anyString(), anyString(), anyString());
        listener.onParcelProgressed(new ParcelProgressed(UUID.randomUUID(), REF, BUYER,
                DeliveryMethod.DELIVERY, FulfilmentStatus.DELIVERED, false, parcel));
        verify(recorder).record(parcel, BuyerNoticeKind.DELIVERED_BY_SELLER,
                BuyerNoticeOutcome.WHATSAPP);

        properties.getParcelUpdates().setEnabled(false);
        listener.onParcelProgressed(new ParcelProgressed(UUID.randomUUID(), REF, BUYER,
                DeliveryMethod.DELIVERY, FulfilmentStatus.DISPATCHED, false, parcel));
        verify(recorder).record(parcel, BuyerNoticeKind.DISPATCHED, BuyerNoticeOutcome.NOT_SENT);
    }
}
