package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.OrderPaid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The order-paid listener's routing + guard rails: SMS primary, WhatsApp
 * fallback only on SMS failure, {@code outcome=disabled} without a single wire
 * attempt when no channel is configured, and — the invariant that matters most
 * on an after-commit path — <b>nothing ever escapes the listener</b>, because
 * an exception there would make a dead SMS gateway look like a failed payment
 * confirm.
 */
class OrderPaidNotificationListenerTest {

    private static final String EXPECTED_MESSAGE =
            "Your InnBucks Marketplace order MKT-4F2A9C1B77D0 (USD 31.00) is confirmed. "
                    + "Ref MKT-4F2A9C1B77D0";

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private MerchantOrderNotifier merchantNotifier;
    private SimpleMeterRegistry registry;
    private OrderPaidNotificationListener listener;

    private final OrderPaid event = new OrderPaid(UUID.randomUUID(), "MKT-4F2A9C1B77D0",
            "+263771234567", 3100, "USD");

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        merchantNotifier = mock(MerchantOrderNotifier.class);
        registry = new SimpleMeterRegistry();
        listener = new OrderPaidNotificationListener(sms, whatsApp, merchantNotifier,
                new MarketplaceMetrics(registry));
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "order_paid").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("SMS configured + delivers: outcome=sent, order ref as gateway reference, no WhatsApp")
    void smsHappyPath() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);

        listener.onOrderPaid(event);

        verify(sms).sendSms("+263771234567", EXPECTED_MESSAGE, "MKT-4F2A9C1B77D0");
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(outcome("sent")).isEqualTo(1.0);
        verify(merchantNotifier).notifyMerchants(event);
    }

    @Test
    @DisplayName("SMS fails + WhatsApp configured: fallback carries the SAME message, outcome=fallback")
    void smsFailure_fallsBackToWhatsApp() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new NotificationDeliveryException("gateway 500"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        listener.onOrderPaid(event);

        verify(whatsApp).sendCustomNotification("+263771234567", EXPECTED_MESSAGE);
        assertThat(outcome("fallback")).isEqualTo(1.0);
        assertThat(outcome("failed")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("SMS fails + WhatsApp NOT configured: outcome=failed, WhatsApp never attempted")
    void smsFailure_noFallbackConfigured() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(false);
        doThrow(new NotificationDeliveryException("gateway 500"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        listener.onOrderPaid(event);

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("both channels fail: outcome=failed, listener still never throws")
    void bothChannelsFail_neverThrows() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("whatsapp down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatCode(() -> listener.onOrderPaid(event)).doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no channel configured: outcome=disabled, ZERO send attempts (verify never)")
    void nothingConfigured_disabledNoOp() {
        when(sms.isConfigured()).thenReturn(false);
        when(whatsApp.isConfigured()).thenReturn(false);

        listener.onOrderPaid(event);

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(outcome("disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SMS not configured but WhatsApp is: delivered over WhatsApp, outcome=fallback")
    void smsDisabled_whatsAppOnly() {
        when(sms.isConfigured()).thenReturn(false);
        when(whatsApp.isConfigured()).thenReturn(true);

        listener.onOrderPaid(event);

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(whatsApp).sendCustomNotification("+263771234567", EXPECTED_MESSAGE);
        assertThat(outcome("fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unexpected explosion (not a delivery failure) is swallowed and metered")
    void unexpectedExplosion_swallowed() {
        when(sms.isConfigured()).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.onOrderPaid(event)).doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
        // The merchant fan-out still runs — one leg's failure never suppresses
        // the other.
        verify(merchantNotifier).notifyMerchants(event);
    }

    @Test
    @DisplayName("a merchant-notifier explosion cannot escape the after-commit path")
    void merchantNotifierExplosion_neverEscapes() {
        when(sms.isConfigured()).thenReturn(false);
        when(whatsApp.isConfigured()).thenReturn(false);
        // MerchantOrderNotifier promises never to throw; the listener still
        // wraps it (defence in depth) — pinned here from the caller's side.
        doThrow(new IllegalStateException("must not happen"))
                .when(merchantNotifier).notifyMerchants(any());

        assertThatCode(() -> listener.onOrderPaid(event)).doesNotThrowAnyException();
    }
}
