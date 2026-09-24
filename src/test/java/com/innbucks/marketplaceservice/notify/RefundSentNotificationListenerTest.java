package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.settlement.RefundSent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
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
 * The refund-sent listener: the second half of a cancelled parcel's promise.
 * It runs AFTER_COMMIT, so nothing may escape it — an exception would reach
 * the operator as a failed refund recording for a refund that was recorded.
 */
class RefundSentNotificationListenerTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final String REF = "MKT-4F2A9C1B77D0";
    private static final String BUYER = "+263771234567";

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private MarketOrderRepository orders;
    private SimpleMeterRegistry registry;
    private RefundSentNotificationListener listener;

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        orders = mock(MarketOrderRepository.class);
        registry = new SimpleMeterRegistry();
        listener = new RefundSentNotificationListener(sms, whatsApp, orders,
                new MarketplaceMetrics(registry));
        MarketOrder order = new MarketOrder();
        order.setId(ORDER_ID);
        order.setOrderRef(REF);
        order.setBuyerMsisdn(BUYER);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private static RefundSent event() {
        return new RefundSent(ORDER_ID, UUID.randomUUID(), 2350, "USD", "IB-778812");
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "refund_sent").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("The buyer is told by SMS that the refund has left, with the amount and reference")
    void sendsBySms() {
        when(sms.isConfigured()).thenReturn(true);

        listener.onRefundSent(event());

        verify(sms).sendSms(eq(BUYER), eq("Your refund of USD 23.50 for InnBucks Marketplace "
                + "order MKT-4F2A9C1B77D0 has been sent. Refund reference IB-778812. "
                + "Ref MKT-4F2A9C1B77D0"), eq(REF));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(outcome("sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A failed SMS falls back to WhatsApp")
    void fallsBackToWhatsApp() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        listener.onRefundSent(event());

        verify(whatsApp).sendCustomNotification(eq(BUYER), anyString());
        assertThat(outcome("fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Both channels failing is counted and swallowed, never thrown")
    void neverThrows() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("down")).when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("down")).when(whatsApp)
                .sendCustomNotification(anyString(), anyString());

        assertThatCode(() -> listener.onRefundSent(event())).doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("No channel configured sends nothing and reads no order")
    void disabled() {
        listener.onRefundSent(event());

        verify(orders, never()).findById(any());
        assertThat(outcome("disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("An order that cannot be read is counted, not thrown")
    void missingOrder() {
        when(sms.isConfigured()).thenReturn(true);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.onRefundSent(event())).doesNotThrowAnyException();
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        assertThat(outcome("no_recipient")).isEqualTo(1.0);
    }
}
