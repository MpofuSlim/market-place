package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.OrderPaid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every notification template's EXACT wording (a copy change must be a
 * deliberate, reviewed diff) and the fleet composer discipline: every template
 * round-trips {@link SmsTextSanitizer} UNCHANGED — the SMS gateway 400s on
 * {@code ! : / ? " * ;}, so a colon or slash creeping into the copy fails the
 * build here instead of in production.
 */
class OrderNotificationComposerTest {

    private static final String REF = "MKT-4F2A9C1B77D0";

    private OrderPaid paid(long totalCents) {
        return new OrderPaid(UUID.randomUUID(), REF, "+263771234567", totalCents, "USD");
    }

    private MarketOrderItem item(String title, int quantity, long lineTotalCents) {
        return MarketOrderItem.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .listingId(UUID.randomUUID())
                .titleSnapshot(title)
                .unitPriceCents(lineTotalCents / quantity)
                .quantity(quantity)
                .lineTotalCents(lineTotalCents)
                .build();
    }

    @Test
    @DisplayName("buyer order-paid SMS: exact wording, total in major units")
    void buyerOrderPaidMessage_exactWording() {
        assertThat(OrderNotificationComposer.buyerOrderPaidMessage(paid(2599)))
                .isEqualTo("Your InnBucks Marketplace order MKT-4F2A9C1B77D0 (USD 25.99) "
                        + "is confirmed. Ref MKT-4F2A9C1B77D0");
    }

    @Test
    @DisplayName("merchant new-paid-order message: exact wording, merchant subtotal only")
    void merchantOrderMessage_exactWording() {
        String message = OrderNotificationComposer.merchantOrderMessage(REF,
                List.of(item("Solar Lantern", 2, 5198)), "USD");
        assertThat(message)
                .isEqualTo("New paid order MKT-4F2A9C1B77D0. 2 x Solar Lantern - USD 51.98");
        assertThat(OrderNotificationComposer.merchantOrderSubject(REF))
                .isEqualTo("New paid order MKT-4F2A9C1B77D0");
    }

    @Test
    @DisplayName("merchant message with several lines: comma-joined, subtotal is the SUM")
    void merchantOrderMessage_multiLine() {
        String message = OrderNotificationComposer.merchantOrderMessage(REF,
                List.of(item("Solar Lantern", 2, 5198), item("Garden Hose", 1, 2599)), "USD");
        assertThat(message).isEqualTo(
                "New paid order MKT-4F2A9C1B77D0. 2 x Solar Lantern, 1 x Garden Hose - USD 77.97");
    }

    @Test
    @DisplayName("restock alert: exact wording, listing price in major units")
    void restockMessage_exactWording() {
        Listing listing = new Listing();
        listing.setId(UUID.randomUUID());
        listing.setTitle("Solar Lantern 20W");
        listing.setPriceCents(1550);
        listing.setCurrency("USD");
        assertThat(OrderNotificationComposer.restockMessage(listing))
                .isEqualTo("Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace");
        assertThat(OrderNotificationComposer.restockSubject())
                .isEqualTo("Back in stock on InnBucks Marketplace");
    }

    @Test
    @DisplayName("money renders major units with two decimals, Locale-proof")
    void money_majorUnits() {
        assertThat(OrderNotificationComposer.money(5, "USD")).isEqualTo("USD 0.05");
        assertThat(OrderNotificationComposer.money(100, "USD")).isEqualTo("USD 1.00");
        assertThat(OrderNotificationComposer.money(123456789, "ZWL")).isEqualTo("ZWL 1234567.89");
    }

    @Test
    @DisplayName("every template survives the GSM sanitizer UNCHANGED (fleet composer discipline)")
    void everyTemplateRoundTripsTheGsmSanitizer() {
        Listing listing = new Listing();
        listing.setId(UUID.randomUUID());
        listing.setTitle("Solar Lantern 20W");
        listing.setPriceCents(1550);
        listing.setCurrency("USD");

        List<String> templates = List.of(
                OrderNotificationComposer.buyerOrderPaidMessage(paid(2599)),
                OrderNotificationComposer.merchantOrderSubject(REF),
                OrderNotificationComposer.merchantOrderMessage(REF,
                        List.of(item("Solar Lantern", 2, 5198), item("Garden Hose", 1, 2599)), "USD"),
                OrderNotificationComposer.restockSubject(),
                OrderNotificationComposer.restockMessage(listing));
        for (String template : templates) {
            assertThat(SmsTextSanitizer.toGsmSafe(template))
                    .as("template must be GSM-safe as composed: %s", template)
                    .isEqualTo(template);
        }
    }
}
