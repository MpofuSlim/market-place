package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.OrderPaid;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Merchant fan-out mechanics: grouping by the OWNING listing's merchant, one
 * per-merchant message with THAT merchant's subtotal, delivery per resolved
 * admin uuid — plus the guard rails: disabled-by-default no-op (zero repo/
 * gateway touches), no-recipients metering (the shipped
 * {@link MerchantAdminResolver.Unavailable} state), and never-throws.
 */
class MerchantOrderNotifierTest {

    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();
    private static final String REF = "MKT-4F2A9C1B77D0";

    private MarketplaceNotificationProperties properties;
    private MarketOrderItemRepository itemRepository;
    private ListingRepository listingRepository;
    private MerchantAdminResolver resolver;
    private UserNotifyGateway gateway;
    private SimpleMeterRegistry registry;
    private MerchantOrderNotifier notifier;

    private final OrderPaid event = new OrderPaid(UUID.randomUUID(), REF,
            "+263771234567", 7797, "USD");

    @BeforeEach
    void setUp() {
        properties = new MarketplaceNotificationProperties();
        itemRepository = mock(MarketOrderItemRepository.class);
        listingRepository = mock(ListingRepository.class);
        resolver = mock(MerchantAdminResolver.class);
        gateway = mock(UserNotifyGateway.class);
        registry = new SimpleMeterRegistry();
        notifier = new MerchantOrderNotifier(properties, itemRepository, listingRepository,
                resolver, gateway, new MarketplaceMetrics(registry));
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "merchant_order").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Listing listing(UUID id, UUID merchantId) {
        Listing listing = new Listing();
        listing.setId(id);
        listing.setMerchantId(merchantId);
        return listing;
    }

    private MarketOrderItem item(UUID listingId, String title, int qty, long lineTotalCents) {
        return MarketOrderItem.builder()
                .id(UUID.randomUUID())
                .orderId(event.orderId())
                .listingId(listingId)
                .titleSnapshot(title)
                .unitPriceCents(lineTotalCents / qty)
                .quantity(qty)
                .lineTotalCents(lineTotalCents)
                .build();
    }

    @Test
    @DisplayName("DISABLED (the default): outcome=disabled, zero repository or gateway touches")
    void disabledByDefault_noOp() {
        assertThat(properties.getMerchantOrders().isEnabled()).isFalse();

        notifier.notifyMerchants(event);

        verifyNoInteractions(itemRepository, listingRepository, resolver, gateway);
        assertThat(outcome("disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("enabled: lines grouped per merchant, each admin notified with that merchant's subtotal")
    void enabled_groupsByMerchantAndNotifiesEachAdmin() {
        properties.getMerchantOrders().setEnabled(true);
        UUID listingA1 = UUID.randomUUID();
        UUID listingA2 = UUID.randomUUID();
        UUID listingB = UUID.randomUUID();
        when(itemRepository.findByOrderId(event.orderId())).thenReturn(List.of(
                item(listingA1, "Solar Lantern", 2, 5198),
                item(listingB, "Garden Hose", 1, 2599),
                item(listingA2, "Torch", 1, 1000)));
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listing(listingA1, MERCHANT_A), listing(listingA2, MERCHANT_A),
                listing(listingB, MERCHANT_B)));
        UUID adminA1 = UUID.randomUUID();
        UUID adminA2 = UUID.randomUUID();
        UUID adminB = UUID.randomUUID();
        when(resolver.adminUserUuids(MERCHANT_A)).thenReturn(List.of(adminA1, adminA2));
        when(resolver.adminUserUuids(MERCHANT_B)).thenReturn(List.of(adminB));
        when(gateway.notify(any(), anyString(), anyString())).thenReturn(true);

        notifier.notifyMerchants(event);

        String subject = "New paid order MKT-4F2A9C1B77D0";
        String messageA = "New paid order MKT-4F2A9C1B77D0. 2 x Solar Lantern, 1 x Torch - USD 61.98";
        String messageB = "New paid order MKT-4F2A9C1B77D0. 1 x Garden Hose - USD 25.99";
        verify(gateway).notify(adminA1, subject, messageA);
        verify(gateway).notify(adminA2, subject, messageA);
        verify(gateway).notify(adminB, subject, messageB);
        assertThat(outcome("sent")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("no resolvable admins (the shipped Unavailable resolver): outcome=no_recipients, nothing sent")
    void enabled_noResolvableAdmins() {
        properties.getMerchantOrders().setEnabled(true);
        UUID listingId = UUID.randomUUID();
        when(itemRepository.findByOrderId(event.orderId()))
                .thenReturn(List.of(item(listingId, "Solar Lantern", 2, 5198)));
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listing(listingId, MERCHANT_A)));
        when(resolver.adminUserUuids(MERCHANT_A)).thenReturn(List.of());

        notifier.notifyMerchants(event);

        verifyNoInteractions(gateway);
        assertThat(outcome("no_recipients")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("gateway refusal counts outcome=failed per admin, delivery to the rest continues")
    void gatewayRefusal_countedPerAdmin() {
        properties.getMerchantOrders().setEnabled(true);
        UUID listingId = UUID.randomUUID();
        when(itemRepository.findByOrderId(event.orderId()))
                .thenReturn(List.of(item(listingId, "Solar Lantern", 2, 5198)));
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listing(listingId, MERCHANT_A)));
        UUID admin1 = UUID.randomUUID();
        UUID admin2 = UUID.randomUUID();
        when(resolver.adminUserUuids(MERCHANT_A)).thenReturn(List.of(admin1, admin2));
        when(gateway.notify(eq(admin1), anyString(), anyString())).thenReturn(false);
        when(gateway.notify(eq(admin2), anyString(), anyString())).thenReturn(true);

        notifier.notifyMerchants(event);

        assertThat(outcome("failed")).isEqualTo(1.0);
        assertThat(outcome("sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an exploding repository never escapes (never-throws contract), outcome=failed")
    void repositoryExplosion_swallowed() {
        properties.getMerchantOrders().setEnabled(true);
        when(itemRepository.findByOrderId(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> notifier.notifyMerchants(event)).doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }
}
