package com.innbucks.marketplaceservice.notify;

/**
 * Thrown when an outbound notification gateway (the InnBucks notification API,
 * the WhatsApp gateway, SMTP) rejects a message or is unreachable. Every
 * marketplace trigger treats it as best-effort — logged, metered and swallowed
 * — so a gateway hiccup never affects the already-committed order or restock.
 *
 * <p>Copied from the ticketing fleet (booking-service / InnRewards carry the
 * same class) — the marketplace holds its own copy because it depends on no
 * fleet module.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
