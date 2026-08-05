package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link OrderExpirySweeper} directly (the test profile disables its
 * cron with {@code "-"}) against seeded rows in a real Postgres. Pins the
 * seat-hold philosophy end to end: a lapsed PENDING_PAYMENT order EXPIRES and
 * returns its stock EXACTLY ONCE ({@code stock_released} double-release
 * guard), while an unlapsed order is untouched. The direct call still goes
 * through the ShedLock proxy, so the {@code shedlock} lock table from V1 is
 * exercised too.
 */
class ExpirySweepIT extends PostgresTestContainer {

    @Autowired
    private OrderExpirySweeper sweeper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private MarketOrderRepository orderRepository;

    @Autowired
    private MarketOrderItemRepository itemRepository;

    @Test
    void lapsedOrderExpiresAndRestocksExactlyOnce() {
        UUID listingId = seedActiveListing(3);
        // 2 units were reserved at order time (stock already decremented to 3).
        UUID orderId = seedPendingOrder("MKT-EXPIRE000001", listingId, 2,
                Instant.now().minus(5, ChronoUnit.MINUTES));

        sweeper.sweep();

        MarketOrder expired = orderRepository.findById(orderId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.isStockReleased()).isTrue();
        assertThat(stockOf(listingId)).isEqualTo(5);

        // Second pass is a no-op: EXPIRED is terminal, and even a direct
        // re-expiry attempt is refused before the stock_released guard could
        // matter — stock is returned exactly once.
        sweeper.sweep();
        assertThat(orderService.expireOne(orderId)).isFalse();

        MarketOrder after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(after.isStockReleased()).isTrue();
        assertThat(stockOf(listingId)).isEqualTo(5);
    }

    @Test
    void unlapsedOrderIsLeftAlone() {
        UUID listingId = seedActiveListing(3);
        UUID orderId = seedPendingOrder("MKT-NOTDUE000001", listingId, 2,
                Instant.now().plus(20, ChronoUnit.MINUTES));

        sweeper.sweep();

        MarketOrder untouched = orderRepository.findById(orderId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(untouched.isStockReleased()).isFalse();
        assertThat(stockOf(listingId)).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Seeding — through the repositories (manual UUID ids + null @Version
    // mark the rows as new, so save() INSERTs).
    // ------------------------------------------------------------------

    private UUID seedActiveListing(int stockQty) {
        Instant now = Instant.now();
        Listing listing = Listing.builder()
                .id(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .title("Solar Lantern 20W")
                .priceCents(1550L)
                .currency("USD")
                .stockQty(stockQty)
                .status(ListingStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        listingRepository.save(listing);
        return listing.getId();
    }

    private UUID seedPendingOrder(String orderRef, UUID listingId, int quantity, Instant expiresAt) {
        Instant createdAt = Instant.now().minus(40, ChronoUnit.MINUTES);
        MarketOrder order = MarketOrder.builder()
                .id(UUID.randomUUID())
                .orderRef(orderRef)
                .buyerUuid(UUID.randomUUID())
                .buyerMsisdn("+263771234567")
                .status(OrderStatus.PENDING_PAYMENT)
                .totalCents(1550L * quantity)
                .currency("USD")
                .expiresAt(expiresAt)
                .stockReleased(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
        orderRepository.save(order);
        itemRepository.save(MarketOrderItem.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .listingId(listingId)
                .titleSnapshot("Solar Lantern 20W")
                .unitPriceCents(1550L)
                .quantity(quantity)
                .lineTotalCents(1550L * quantity)
                .build());
        return order.getId();
    }

    private int stockOf(UUID listingId) {
        return listingRepository.findById(listingId).orElseThrow().getStockQty();
    }
}
