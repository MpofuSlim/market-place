package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.settlement.DisputeReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tells a SELLER — every OWNER and ADMIN of the selling organization — about
 * something that needs them, in the portal bell with a type and a link to the
 * right screen (user-service then picks the outbound channel per person).
 *
 * <p>Each alert exists because the seller otherwise learns too late: a dispute
 * freezes their money, a collection left on the shelf drifts onto the
 * operator's stale list, a payout lands with no word, a buyer's SMS silently
 * fails, a buyer cancels something the seller is about to pack.
 *
 * <p><b>Strictly best-effort and never throws.</b> Callers are after-commit
 * listeners and a scheduled sweep; a notification problem must never reach
 * the money path that caused it. Everything is logged and metered
 * ({@code marketplace.notifications{type=seller_<kind>}}).
 *
 * <p>The notice {@code type}s are user-service {@code NotificationType} names.
 * The ones not yet in that constants holder are stored and served as-is — the
 * ingress deliberately never refuses an unknown type.
 */
@Slf4j
@Component
public class SellerAlertService {

    static final String TYPE_ORDER_PAID = "ORDER_PAID";
    static final String TYPE_PARCEL_DISPUTED = "PARCEL_DISPUTED";
    static final String TYPE_COLLECTION_OVERDUE = "COLLECTION_OVERDUE";
    static final String TYPE_PAYOUT_SENT = "PAYOUT_SENT";
    static final String TYPE_BUYER_NOT_NOTIFIED = "BUYER_NOT_NOTIFIED";
    static final String TYPE_ORDER_CANCELLED_BY_BUYER = "ORDER_CANCELLED_BY_BUYER";

    private final MarketplaceNotificationProperties properties;
    private final MerchantAdminResolver adminResolver;
    private final UserNotifyGateway gateway;
    private final MarketplaceMetrics metrics;

    public SellerAlertService(MarketplaceNotificationProperties properties,
                              MerchantAdminResolver adminResolver,
                              UserNotifyGateway gateway,
                              MarketplaceMetrics metrics) {
        this.properties = properties;
        this.adminResolver = adminResolver;
        this.gateway = gateway;
        this.metrics = metrics;
    }

    public void disputeOpened(UUID merchantId, String orderRef, UUID fulfilmentId,
                              DisputeReason reason) {
        send(merchantId, "seller_dispute", new UserNotice(
                OrderNotificationComposer.disputeOpenedSubject(orderRef),
                OrderNotificationComposer.disputeOpenedMessage(orderRef, label(reason)),
                TYPE_PARCEL_DISPUTED, UserNotice.WARNING, "PARCEL", str(fulfilmentId),
                parcelLink(orderRef)));
    }

    public void collectionOverdue(UUID merchantId, String orderRef, UUID fulfilmentId, int days) {
        send(merchantId, "seller_collection_overdue", new UserNotice(
                OrderNotificationComposer.collectionOverdueSubject(orderRef),
                OrderNotificationComposer.collectionOverdueMessage(orderRef, days),
                TYPE_COLLECTION_OVERDUE, UserNotice.WARNING, "PARCEL", str(fulfilmentId),
                parcelLink(orderRef)));
    }

    public void payoutSent(UUID merchantId, String payoutReference, int parcels, long netCents,
                           String currency) {
        send(merchantId, "seller_payout", new UserNotice(
                OrderNotificationComposer.payoutSentSubject(netCents, currency),
                OrderNotificationComposer.payoutSentMessage(netCents, currency, parcels,
                        payoutReference),
                TYPE_PAYOUT_SENT, UserNotice.SUCCESS, "PAYOUT", payoutReference,
                properties.getSellerAlerts().getEarningsLink()));
    }

    /** @param what completes "We could not message the buyer of order X ...". */
    public void buyerNotReached(UUID merchantId, String orderRef, UUID fulfilmentId, String what) {
        send(merchantId, "seller_buyer_not_reached", new UserNotice(
                OrderNotificationComposer.buyerNotReachedSubject(orderRef),
                OrderNotificationComposer.buyerNotReachedMessage(orderRef, what),
                TYPE_BUYER_NOT_NOTIFIED, UserNotice.WARNING, "PARCEL", str(fulfilmentId),
                parcelLink(orderRef)));
    }

    public void cancelledByBuyer(UUID merchantId, String orderRef, UUID fulfilmentId,
                                 String buyerReason) {
        send(merchantId, "seller_buyer_cancelled", new UserNotice(
                OrderNotificationComposer.cancelledByBuyerSubject(orderRef),
                OrderNotificationComposer.cancelledByBuyerMessage(orderRef, buyerReason),
                TYPE_ORDER_CANCELLED_BY_BUYER, UserNotice.WARNING, "PARCEL", str(fulfilmentId),
                parcelLink(orderRef)));
    }

    /** The portal screen for one order's parcel, from the deployment's template. */
    public String parcelLink(String orderRef) {
        return properties.getSellerAlerts().getParcelLink()
                .replace("{orderRef}", orderRef == null ? "" : orderRef);
    }

    void send(UUID merchantId, String metricType, UserNotice notice) {
        try {
            if (!properties.getSellerAlerts().isEnabled()) {
                metrics.notificationOutcome(metricType, "disabled");
                return;
            }
            List<UUID> admins = adminResolver.adminUserUuids(merchantId);
            if (admins.isEmpty()) {
                metrics.notificationOutcome(metricType, "no_recipients");
                log.info("Seller alert {} not sent - nobody resolvable for merchantId={}",
                        metricType, merchantId);
                return;
            }
            for (UUID admin : admins) {
                metrics.notificationOutcome(metricType,
                        gateway.notify(admin, notice) ? "sent" : "failed");
            }
        } catch (RuntimeException ex) {
            metrics.notificationOutcome(metricType, "failed");
            log.warn("Seller alert {} failed merchantId={} cause={}", metricType, merchantId,
                    ex.toString());
        }
    }

    private static String label(DisputeReason reason) {
        return reason == null ? null : reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
