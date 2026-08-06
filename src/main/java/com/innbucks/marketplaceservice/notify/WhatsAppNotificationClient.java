package com.innbucks.marketplaceservice.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Sends messages through the external WhatsApp notification gateway's
 * {@code POST /api/messages/custom-notification} endpoint (single free-text
 * message, max 1600 chars, authenticated with a lowercase {@code x-api-key}
 * header). Faithful copy of the fleet client (booking-service / InnRewards);
 * the ticketing-specific e-ticket-QR endpoint is deliberately not carried.
 *
 * <p>Used by {@code OrderPaidNotificationListener} as the FALLBACK channel —
 * only when the primary SMS delivery fails and this gateway is configured. Any
 * rejection / connectivity failure is surfaced as a
 * {@link NotificationDeliveryException} so the listener can log and move on
 * (delivery is best-effort).
 */
@Slf4j
@Component
public class WhatsAppNotificationClient {

    private static final String CUSTOM_NOTIFICATION_PATH = "/api/messages/custom-notification";
    private static final String API_KEY_HEADER = "x-api-key";
    static final int MAX_MESSAGE_LENGTH = 1600;

    private final RestClient restClient;
    private final WhatsAppProperties properties;

    public WhatsAppNotificationClient(@Qualifier("whatsAppRestClient") RestClient restClient,
                                      WhatsAppProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /** True when the WHATSAPP_* gateway config is present (fallback enabled). */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public void sendCustomNotification(String to, String notification) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Recipient phone number is blank");
        }
        if (notification == null || notification.isBlank()) {
            throw new NotificationDeliveryException("Notification message is blank");
        }
        if (notification.length() > MAX_MESSAGE_LENGTH) {
            // REFUSED, not truncated (fleet behaviour): a silently-cut message
            // could drop the part that mattered; the caller composed it, the
            // caller must shorten it.
            throw new NotificationDeliveryException(
                    "Notification exceeds the gateway's " + MAX_MESSAGE_LENGTH + "-character limit");
        }
        try {
            restClient.post()
                    .uri(CUSTOM_NOTIFICATION_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "notification", notification))
                    .retrieve()
                    .toBodilessEntity();
            log.info("WhatsApp notification sent to={}", MsisdnMasking.mask(to));
        } catch (RestClientResponseException ex) {
            log.warn("WhatsApp gateway rejected notification to={} status={} body={}",
                    MsisdnMasking.mask(to), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway rejected the message: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp gateway unreachable to={} message={}", MsisdnMasking.mask(to), ex.getMessage());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway is unreachable: " + ex.getMessage(), ex);
        }
    }
}
