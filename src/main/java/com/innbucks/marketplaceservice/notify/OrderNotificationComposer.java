package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.OrderPaid;

import java.util.List;
import java.util.Locale;

/**
 * Composes every notification message the marketplace sends. One place so the
 * wording is pinned by ONE test ({@code OrderNotificationComposerTest}) and a
 * copy change is a deliberate, reviewed diff.
 *
 * <p><b>Copy constraints are load-bearing</b> (fleet composer discipline —
 * the SMS gateway 400s on {@code ! : / ? " * ;}): every template here is
 * written to round-trip {@link SmsTextSanitizer} UNCHANGED — hence
 * {@code "Ref MKT-..."} and {@code " - "}, never {@code "Ref:"} or an em-dash.
 * The composer test asserts the round-trip, so a colon creeping back in fails
 * the build. Messages that ride user-service's notify endpoint (restock,
 * merchant) obey the same constraint so they stay safe on ANY channel
 * user-service picks.
 *
 * <p>Money renders in MAJOR units ({@code USD 25.99}) — the wire/storage stays
 * minor-units cents; this is presentation only.
 */
public final class OrderNotificationComposer {

    private OrderNotificationComposer() {
    }

    /**
     * Buyer order-paid SMS, e.g.
     * {@code "Your InnBucks Marketplace order MKT-4F2A9C1B77D0 (USD 25.99) is
     * confirmed. Ref MKT-4F2A9C1B77D0"}. Short and single-purpose: the app
     * shows the full order; this is the payment receipt in the buyer's pocket.
     */
    public static String buyerOrderPaidMessage(OrderPaid order) {
        String money = money(order.totalCents(), order.currency());
        return "Your InnBucks Marketplace order " + order.orderRef()
                + " (" + money + ") is confirmed. Ref " + order.orderRef();
    }

    /** Subject used when the buyer message rides a subject-bearing channel. */
    public static String merchantOrderSubject(String orderRef) {
        return "New paid order " + orderRef;
    }

    /**
     * Merchant new-paid-order message covering THAT merchant's lines only,
     * e.g. {@code "New paid order MKT-4F2A9C1B77D0. 2 x Solar Lantern - USD
     * 51.98"} — multi-line orders list every line, and the amount is the
     * MERCHANT'S subtotal, never the whole order's total (which may contain
     * other merchants' money).
     */
    public static String merchantOrderMessage(String orderRef, List<MarketOrderItem> merchantItems,
                                              String currency) {
        long subtotal = 0;
        StringBuilder lines = new StringBuilder();
        for (MarketOrderItem item : merchantItems) {
            if (!lines.isEmpty()) {
                lines.append(", ");
            }
            lines.append(item.getQuantity()).append(" x ").append(item.getTitleSnapshot());
            subtotal += item.getLineTotalCents();
        }
        return "New paid order " + orderRef + ". " + lines + " - " + money(subtotal, currency);
    }

    /** Subject for the back-in-stock alert. */
    public static String restockSubject() {
        return "Back in stock on InnBucks Marketplace";
    }

    /**
     * Back-in-stock alert for a favorited listing, e.g. {@code "Back in stock.
     * Solar Lantern 20W - USD 15.50 on InnBucks Marketplace"}.
     */
    public static String restockMessage(Listing listing) {
        return "Back in stock. " + listing.getTitle() + " - "
                + money(listing.getPriceCents(), listing.getCurrency())
                + " on InnBucks Marketplace";
    }

    /**
     * Cents → major units, e.g. {@code (2599, "USD") -> "USD 25.99"}. Always
     * two decimals; Locale.ROOT so a JVM locale can never swap the separator.
     */
    static String money(long cents, String currency) {
        return String.format(Locale.ROOT, "%s %d.%02d", currency, cents / 100, cents % 100);
    }
}
