package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.OrderPaid;
import com.innbucks.marketplaceservice.seller.PayoutMethod;

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

    /** Subject used when the gift alert rides a subject-bearing channel. */
    public static String giftSubject() {
        return "A gift is on its way to you";
    }

    /**
     * Told to the RECIPIENT of a gift order once it is paid, e.g. {@code "You
     * have a gift coming on InnBucks Marketplace. Order MKT-4F2A9C1B77D0. Note
     * - Happy birthday Gogo, love from Tari"}.
     *
     * <p>Deliberately says nothing about what was bought or what it cost: the
     * recipient is not a party to the purchase, and a gift that announces its
     * own price is not much of a gift. The buyer's note is where the sender
     * identifies themselves — the platform does not know their name, and
     * inventing one ("Tarisai sent you...") from an account it cannot vouch for
     * would be worse than saying nothing.
     *
     * <p>The note is BUYER free text. Only the fixed parts of this template are
     * pinned to round-trip {@link SmsTextSanitizer}; the note is sanitized by
     * the SMS client on the way out like any other body.
     */
    public static String giftRecipientMessage(String orderRef, String giftMessage) {
        String base = "You have a gift coming on InnBucks Marketplace. Order " + orderRef;
        return giftMessage == null || giftMessage.isBlank()
                ? base
                : base + ". Note - " + giftMessage.trim();
    }

    /** Subject for the collection-code message. */
    public static String collectCodeSubject() {
        return "Your collection code";
    }

    /**
     * The collection code, sent to whoever is collecting, e.g. {@code "Your
     * InnBucks Marketplace collection code for order MKT-4F2A9C1B77D0 is
     * K7Q2-9XMF-3TRW. Show it when you collect."}
     *
     * <p>Grouped form, because this is read off a phone and said out loud at a
     * counter. It names no seller and no goods: a code is useless without the
     * parcel it belongs to, and the fewer places the two travel together, the
     * less a stolen phone is worth.
     */
    public static String collectCodeMessage(String orderRef, String groupedCode) {
        return "Your InnBucks Marketplace collection code for order " + orderRef
                + " is " + groupedCode + ". Show it when you collect.";
    }

    /** Subject used when the unfulfillable-parcel notice rides a
     *  subject-bearing channel. */
    public static String parcelUnfulfilledSubject(String orderRef) {
        return "A problem with order " + orderRef;
    }

    /**
     * Told to the BUYER when a seller declares they cannot supply a parcel,
     * e.g. {@code "Sorry - a seller cannot supply part of your InnBucks
     * Marketplace order MKT-4F2A9C1B77D0. Reason - out of stock. A refund of
     * USD 15.50 is being arranged. Ref MKT-4F2A9C1B77D0"}.
     *
     * <p>Says "part of" without qualification because a single-seller order is
     * still literally part of it, and a buyer reading this will open the app
     * to see which. Names the amount when money was actually queued for refund
     * and stays quiet about it when not — a parcel whose money was already
     * disputed or paid out turns nothing around, and promising a refund the
     * ledger has not queued is the one sentence here that must never be wrong.
     *
     * <p>The seller's reason is THEIR free text; only the fixed parts of this
     * template are pinned to round-trip {@link SmsTextSanitizer}.
     */
    public static String parcelUnfulfilledMessage(String orderRef, String sellerReason,
                                                  long refundDueCents, String currency) {
        StringBuilder message = new StringBuilder("Sorry - a seller cannot supply part of your "
                + "InnBucks Marketplace order " + orderRef + ".");
        if (sellerReason != null && !sellerReason.isBlank()) {
            message.append(" Reason - ").append(sellerReason.trim());
            if (!sellerReason.trim().endsWith(".")) {
                message.append('.');
            }
        }
        message.append(refundDueCents > 0
                ? " A refund of " + money(refundDueCents, currency) + " is being arranged."
                : " Our support team will be in touch.");
        return message.append(" Ref ").append(orderRef).toString();
    }

    /** Subject for the payout-destination change warning. */
    public static String payoutDestinationSubject() {
        return "Your InnBucks Marketplace payout details changed";
    }

    /**
     * Told to a SELLER when their payout destination is set or changed, e.g.
     * {@code "Your InnBucks Marketplace payout details were changed to a bank
     * account. If this was not you, contact support now."}.
     *
     * <p>Names the METHOD and nothing else — no account number, no phone
     * number. Enough for the reader to know whether it matches what they just
     * did; useless to anyone who intercepts it. A message that quoted the new
     * destination would hand an attacker holding the phone a confirmation
     * receipt, and hand anyone else who reads it the account.
     *
     * <p>The closing line differs by who acted, because "we did this for you"
     * and "someone did this" call for different reactions — and the FIRST
     * destination a seller sets is not a change to be alarmed about.
     */
    public static String payoutDestinationMessage(PayoutMethod method,
                                                  boolean replacedAnExistingOne,
                                                  boolean changedBySeller) {
        String rail = method == PayoutMethod.BANK ? "a bank account" : "a mobile money number";
        StringBuilder message = new StringBuilder("Your InnBucks Marketplace payout details ");
        message.append(replacedAnExistingOne ? "were changed to " : "were set to ").append(rail);
        message.append('.');
        if (!changedBySeller) {
            message.append(" This was done by our support team.");
        }
        // Only a CHANGE warrants an alarm; being told to check something you
        // just created for the first time trains people to ignore the line.
        if (replacedAnExistingOne) {
            message.append(" If this was not you, contact support now.");
        }
        return message.toString();
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
