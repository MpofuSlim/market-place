package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.seller.PayoutDestinationChanged;
import com.innbucks.marketplaceservice.seller.PayoutMethod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payout-destination warning's routing and guard rails.
 *
 * <p>This is the notification that turns a redirected payout from something a
 * seller discovers weeks later into a phone call the same minute — so the
 * cases that matter are that it reaches every admin, that it never carries the
 * account, and that nothing escapes it (an exception on this AFTER_COMMIT path
 * would make a dead gateway look like a refused update).
 */
class PayoutDestinationNotificationListenerTest {

    private static final UUID MERCHANT = UUID.randomUUID();

    private MerchantAdminResolver resolver;
    private UserNotifyGateway gateway;
    private SimpleMeterRegistry registry;
    private PayoutDestinationNotificationListener listener;

    @BeforeEach
    void setUp() {
        resolver = mock(MerchantAdminResolver.class);
        gateway = mock(UserNotifyGateway.class);
        registry = new SimpleMeterRegistry();
        listener = new PayoutDestinationNotificationListener(
                resolver, gateway, new MarketplaceMetrics(registry));
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "payout_destination").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static PayoutDestinationChanged changed() {
        return new PayoutDestinationChanged(MERCHANT, PayoutMethod.BANK, true, true);
    }

    @Test
    @DisplayName("Every admin of the merchant is warned")
    void everyAdminIsWarned() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of(a, b));
        when(gateway.notify(any(), anyString(), anyString())).thenReturn(true);

        listener.onPayoutDestinationChanged(changed());

        verify(gateway).notify(eq(a), anyString(), contains("contact support now"));
        verify(gateway).notify(eq(b), anyString(), anyString());
        assertThat(outcome("sent")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("The message carries the rail and NOT the account")
    void theMessageNamesNoAccount() {
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of(UUID.randomUUID()));
        when(gateway.notify(any(), anyString(), anyString())).thenReturn(true);

        listener.onPayoutDestinationChanged(changed());

        // A message quoting the new destination hands an attacker holding the
        // phone a confirmation receipt, and anyone else the account itself.
        verify(gateway).notify(any(), anyString(), contains("a bank account"));
        verify(gateway, never()).notify(any(), anyString(), contains("01123"));
    }

    @Test
    @DisplayName("No resolvable admin: counted, logged, and the change still stands")
    void noResolvableAdminIsCountedNotFatal() {
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of());

        listener.onPayoutDestinationChanged(changed());

        verify(gateway, never()).notify(any(), anyString(), anyString());
        // A warning that cannot be delivered is not a reason to refuse the
        // update — but it IS a seller who can never be warned, hence the meter.
        assertThat(outcome("no_recipients")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A gateway that refuses is counted failed, never thrown")
    void aRefusedDeliveryIsCounted() {
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of(UUID.randomUUID()));
        when(gateway.notify(any(), anyString(), anyString())).thenReturn(false);

        listener.onPayoutDestinationChanged(changed());

        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("NOTHING escapes — an after-commit throw would look like a refused update")
    void nothingEscapes() {
        when(resolver.adminUserUuids(MERCHANT))
                .thenThrow(new IllegalStateException("user-service down"));

        assertThatCode(() -> listener.onPayoutDestinationChanged(changed()))
                .doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }
}
