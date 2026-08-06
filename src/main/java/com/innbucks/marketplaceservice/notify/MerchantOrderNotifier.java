package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.OrderPaid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells each selling merchant's admin users about a newly-PAID order
 * containing their listings: groups the order's lines by the listing's
 * {@code merchant_id}, composes one per-merchant summary (that merchant's
 * lines + subtotal, never the whole order), resolves the admin users via
 * {@link MerchantAdminResolver} and delivers through
 * {@link UserNotifyGateway} (user-service owns channel selection).
 *
 * <p><b>DISABLED BY DEFAULT</b> ({@code marketplace.notifications
 * .merchant-orders.enabled=false}): user-service has no merchantId→admin-users
 * internal lookup yet, so the shipped {@link MerchantAdminResolver.Unavailable}
 * resolves nobody — see the TODO on {@link MerchantAdminResolver}. The
 * grouping/composition/fan-out here is complete and unit-tested; enabling is a
 * config flip once the resolver is real.
 *
 * <p>Called only from the never-throws notification listener, and defensively
 * never throws itself — a notify failure must never surface anywhere near the
 * payment confirm.
 */
@Slf4j
@Component
public class MerchantOrderNotifier {

    private final MarketplaceNotificationProperties properties;
    private final MarketOrderItemRepository itemRepository;
    private final ListingRepository listingRepository;
    private final MerchantAdminResolver adminResolver;
    private final UserNotifyGateway userNotifyGateway;
    private final MarketplaceMetrics metrics;

    public MerchantOrderNotifier(MarketplaceNotificationProperties properties,
                                 MarketOrderItemRepository itemRepository,
                                 ListingRepository listingRepository,
                                 MerchantAdminResolver adminResolver,
                                 UserNotifyGateway userNotifyGateway,
                                 MarketplaceMetrics metrics) {
        this.properties = properties;
        this.itemRepository = itemRepository;
        this.listingRepository = listingRepository;
        this.adminResolver = adminResolver;
        this.userNotifyGateway = userNotifyGateway;
        this.metrics = metrics;
    }

    /** Never throws. Runs post-commit on the notification executor (invoked by
     *  {@code OrderPaidNotificationListener}). */
    public void notifyMerchants(OrderPaid order) {
        try {
            if (!properties.getMerchantOrders().isEnabled()) {
                metrics.notificationOutcome("merchant_order", "disabled");
                return;
            }
            List<MarketOrderItem> items = itemRepository.findByOrderId(order.orderId());
            Map<UUID, List<MarketOrderItem>> byMerchant = groupByMerchant(items);
            for (Map.Entry<UUID, List<MarketOrderItem>> entry : byMerchant.entrySet()) {
                notifyOneMerchant(order, entry.getKey(), entry.getValue());
            }
        } catch (RuntimeException ex) {
            // Best-effort like everything on this path: log, meter, move on.
            metrics.notificationOutcome("merchant_order", "failed");
            log.warn("Merchant order notification failed orderRef={} cause={}",
                    order.orderRef(), ex.toString());
        }
    }

    private void notifyOneMerchant(OrderPaid order, UUID merchantId, List<MarketOrderItem> lines) {
        List<UUID> admins = adminResolver.adminUserUuids(merchantId);
        if (admins.isEmpty()) {
            metrics.notificationOutcome("merchant_order", "no_recipients");
            log.info("No admin users resolvable for merchantId={} orderRef={} — "
                    + "merchant notification skipped", merchantId, order.orderRef());
            return;
        }
        String subject = OrderNotificationComposer.merchantOrderSubject(order.orderRef());
        String message = OrderNotificationComposer.merchantOrderMessage(
                order.orderRef(), lines, order.currency());
        for (UUID admin : admins) {
            boolean accepted = userNotifyGateway.notify(admin, subject, message);
            metrics.notificationOutcome("merchant_order", accepted ? "sent" : "failed");
        }
    }

    /** Groups the order's lines by the OWNING listing's merchant_id (one bulk
     *  listing load, insertion-ordered so composition is deterministic). A line
     *  whose listing row has vanished is skipped — nobody owns it anymore. */
    private Map<UUID, List<MarketOrderItem>> groupByMerchant(List<MarketOrderItem> items) {
        List<UUID> listingIds = items.stream().map(MarketOrderItem::getListingId).toList();
        Map<UUID, UUID> merchantByListing = new LinkedHashMap<>();
        for (Listing listing : listingRepository.findAllById(listingIds)) {
            merchantByListing.put(listing.getId(), listing.getMerchantId());
        }
        Map<UUID, List<MarketOrderItem>> byMerchant = new LinkedHashMap<>();
        for (MarketOrderItem item : items) {
            UUID merchantId = merchantByListing.get(item.getListingId());
            if (merchantId != null) {
                byMerchant.computeIfAbsent(merchantId, k -> new java.util.ArrayList<>()).add(item);
            }
        }
        return byMerchant;
    }
}
