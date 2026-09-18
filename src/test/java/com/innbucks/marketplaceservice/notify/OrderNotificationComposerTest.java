package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.OrderPaid;
import com.innbucks.marketplaceservice.seller.PayoutMethod;
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
        return new OrderPaid(UUID.randomUUID(), REF, "+263771234567", totalCents, "USD", null, null);
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
    @DisplayName("gift alert: names no price and no goods, carries the buyer's note")
    void giftRecipientMessage_exactWording() {
        assertThat(OrderNotificationComposer.giftRecipientMessage(REF,
                "Happy birthday Gogo, love from Tari"))
                .isEqualTo("You have a gift coming on InnBucks Marketplace. "
                        + "Order MKT-4F2A9C1B77D0. Note - Happy birthday Gogo, love from Tari");
        // A gift with no note still announces itself, without a dangling "Note -".
        assertThat(OrderNotificationComposer.giftRecipientMessage(REF, null))
                .isEqualTo("You have a gift coming on InnBucks Marketplace. "
                        + "Order MKT-4F2A9C1B77D0");
        assertThat(OrderNotificationComposer.giftRecipientMessage(REF, "   "))
                .isEqualTo("You have a gift coming on InnBucks Marketplace. "
                        + "Order MKT-4F2A9C1B77D0");
    }

    @Test
    @DisplayName("collection code message: the GROUPED code, no seller and no goods named")
    void collectCodeMessage_exactWording() {
        assertThat(OrderNotificationComposer.collectCodeMessage(REF, "K7Q2-9XMF-3TRW"))
                .isEqualTo("Your InnBucks Marketplace collection code for order "
                        + "MKT-4F2A9C1B77D0 is K7Q2-9XMF-3TRW. Show it when you collect.");
    }

    @Test
    @DisplayName("unfulfilled parcel: names the queued refund amount when money actually turned around")
    void parcelUnfulfilledMessage_withRefund() {
        assertThat(OrderNotificationComposer.parcelUnfulfilledMessage(REF, "out of stock", 1550, "USD"))
                .isEqualTo("Sorry - a seller cannot supply part of your InnBucks Marketplace "
                        + "order MKT-4F2A9C1B77D0. Reason - out of stock. A refund of USD 15.50 "
                        + "is being arranged. Ref MKT-4F2A9C1B77D0");
        assertThat(OrderNotificationComposer.parcelUnfulfilledSubject(REF))
                .isEqualTo("A problem with order MKT-4F2A9C1B77D0");
    }

    @Test
    @DisplayName("unfulfilled parcel: promises NO refund when the ledger queued none")
    void parcelUnfulfilledMessage_withoutRefund() {
        // The parcel's money was already disputed, released or paid out, so
        // nothing turned around. Naming an amount here would be the one
        // sentence in this file that must never be wrong.
        assertThat(OrderNotificationComposer.parcelUnfulfilledMessage(REF, "out of stock", 0, "USD"))
                .isEqualTo("Sorry - a seller cannot supply part of your InnBucks Marketplace "
                        + "order MKT-4F2A9C1B77D0. Reason - out of stock. Our support team will "
                        + "be in touch. Ref MKT-4F2A9C1B77D0")
                .doesNotContain("refund");
    }

    @Test
    @DisplayName("unfulfilled parcel: a reason that already ends in a full stop gains no second one")
    void parcelUnfulfilledMessage_punctuation() {
        assertThat(OrderNotificationComposer.parcelUnfulfilledMessage(REF,
                "  Supplier let us down.  ", 1550, "USD"))
                .contains("Reason - Supplier let us down. A refund")
                .doesNotContain("down..");
        // No reason at all still reads as a sentence, never a dangling "Reason -".
        assertThat(OrderNotificationComposer.parcelUnfulfilledMessage(REF, "   ", 1550, "USD"))
                .isEqualTo("Sorry - a seller cannot supply part of your InnBucks Marketplace "
                        + "order MKT-4F2A9C1B77D0. A refund of USD 15.50 is being arranged. "
                        + "Ref MKT-4F2A9C1B77D0");
    }

    @Test
    @DisplayName("payout-destination warning: names the RAIL and never the account")
    void payoutDestinationMessage_namesTheRailOnly() {
        String message = OrderNotificationComposer.payoutDestinationMessage(
                PayoutMethod.BANK, true, true);
        assertThat(message).isEqualTo("Your InnBucks Marketplace payout details were changed to "
                + "a bank account. If this was not you, contact support now.");
        assertThat(OrderNotificationComposer.payoutDestinationSubject())
                .isEqualTo("Your InnBucks Marketplace payout details changed");
    }

    @Test
    @DisplayName("A FIRST destination is 'were set to', with no alarm line")
    void payoutDestinationMessage_firstSetIsNotAnAlarm() {
        // Telling someone to check something they just created for the first
        // time is how people learn to ignore the line that matters.
        assertThat(OrderNotificationComposer.payoutDestinationMessage(
                PayoutMethod.MOBILE_MONEY, false, true))
                .isEqualTo("Your InnBucks Marketplace payout details were set to "
                        + "a mobile money number.")
                .doesNotContain("contact support");
    }

    @Test
    @DisplayName("An admin override says so — 'we did this' reads differently from 'someone did'")
    void payoutDestinationMessage_adminOverrideSaysSo() {
        assertThat(OrderNotificationComposer.payoutDestinationMessage(
                PayoutMethod.BANK, true, false))
                .contains("This was done by our support team.")
                .contains("If this was not you, contact support now.");
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
                OrderNotificationComposer.restockMessage(listing),
                OrderNotificationComposer.giftSubject(),
                // The buyer's own note is user text, sanitized by the SMS
                // client on the way out; what is pinned here is the template
                // around it.
                OrderNotificationComposer.giftRecipientMessage(REF, "Happy birthday Gogo"),
                OrderNotificationComposer.collectCodeSubject(),
                OrderNotificationComposer.collectCodeMessage(REF, "K7Q2-9XMF-3TRW"),
                OrderNotificationComposer.parcelUnfulfilledSubject(REF),
                // Both branches: the seller's reason is their own free text
                // (sanitized by the SMS client on the way out), so what is
                // pinned here is the fixed copy on either side of it.
                OrderNotificationComposer.parcelUnfulfilledMessage(REF, "out of stock", 1550, "USD"),
                OrderNotificationComposer.parcelUnfulfilledMessage(REF, null, 0, "USD"),
                OrderNotificationComposer.payoutDestinationSubject(),
                // Both branches and both rails: this one carries no caller
                // text at all, so every character of it is pinned here.
                OrderNotificationComposer.payoutDestinationMessage(PayoutMethod.BANK, true, true),
                OrderNotificationComposer.payoutDestinationMessage(
                        PayoutMethod.MOBILE_MONEY, false, false));
        for (String template : templates) {
            assertThat(SmsTextSanitizer.toGsmSafe(template))
                    .as("template must be GSM-safe as composed: %s", template)
                    .isEqualTo(template);
        }
    }
}
