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
         * listings is PAID. ON by default since
         * {@link UserServiceMerchantAdminResolver} landed. The recipients are
         * the OWNERs and ADMINs of the selling organization, from user-service's
         * {@code GET /users/internal/organizations/{id}/admins}.
         *
         * <p>Safe to have on against a cell whose user-service is older than
         * that endpoint: the lookup 404s, resolves nobody, and the notifier
         * meters {@code outcome=no_recipients} — exactly the behaviour this
         * flag used to produce, and no worse than a seller not being told.
         */
        private boolean enabled = true;
    }
}
