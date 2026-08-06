package com.innbucks.marketplaceservice.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Marketplace-side notification switches ({@code marketplace.notifications.*}).
 * These gate the TRIGGERS (which events cause a send); the per-channel
 * enablement is the presence of that channel's credentials
 * ({@link InnbucksNotifyProperties#isConfigured()} /
 * {@link WhatsAppProperties#isConfigured()}).
 */
@Data
@ConfigurationProperties(prefix = "marketplace.notifications")
public class MarketplaceNotificationProperties {

    private final RestockAlerts restockAlerts = new RestockAlerts();
    private final MerchantOrders merchantOrders = new MerchantOrders();

    @Data
    public static class RestockAlerts {
        /** Back-in-stock alerts to favoriters, via user-service's internal
         *  notify endpoint. On by default. */
        private boolean enabled = true;
        /**
         * Cap on recipients per restock event (earliest favoriters win — they
         * waited longest). A viral listing must not turn one merchant stock
         * update into thousands of S2S notify calls; the overflow is logged +
         * metered ({@code outcome=overflow}), never silently dropped.
         */
        private int maxRecipientsPerEvent = 200;
    }

    @Data
    public static class MerchantOrders {
        /**
         * Notify each merchant's admin users when an order containing their
         * listings is PAID. DISABLED BY DEFAULT: user-service currently has NO
         * internal lookup that resolves a merchant's admin USERS by merchantId
         * (verified 2026-08-06 — {@code /users/internal/merchants/assigned}
         * returns merchant ids by role, not users), so
         * {@link MerchantAdminResolver} has no real implementation yet. Flip
         * this on only once user-service ships that small internal endpoint
         * and a resolver bean backs it. See CLAUDE.md "Notifications".
         */
        private boolean enabled = false;
    }
}
