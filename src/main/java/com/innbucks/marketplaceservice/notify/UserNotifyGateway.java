package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Tells user-service to notify a user over their own channels (email →
 * WhatsApp), via the service-to-service {@code POST /users/internal/{uuid}/notify}
 * endpoint authenticated by the shared {@code X-Internal-Token} header (never a
 * user JWT). This is the event-service {@code OrganizerNotificationGateway}
 * pattern: user-service owns the notification credentials, the user's contact
 * details AND the per-user channel selection/fallback, so the marketplace
 * delegates instead of duplicating a contact store.
 *
 * <p>Resolved by service NAME through Eureka ({@code http://user-service} on
 * the {@code @LoadBalanced} builder) — never a hardcoded host:port.
 *
 * <p>Strictly best-effort: user-service returns 202 immediately (delivery is
 * async there), and any failure here (user-service down, timeout, bad token)
 * is logged + metered ({@code marketplace.notifications{type=user_notify}})
 * and NEVER thrown — a notification failure must never fail the restock or
 * order event it accompanies.
 */
@Slf4j
@Component
public class UserNotifyGateway {

    private final RestClient restClient;
    private final String internalToken;
    private final MarketplaceMetrics metrics;

    public UserNotifyGateway(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
                             @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
                             @Value("${user-service.connect-timeout-ms:2000}") int connectTimeoutMs,
                             @Value("${user-service.read-timeout-ms:5000}") int readTimeoutMs,
                             @Value("${innbucks.internal-api-token:}") String internalToken,
                             MarketplaceMetrics metrics) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.clone()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(factory)
                .build();
        this.internalToken = internalToken;
        this.metrics = metrics;
    }

    /**
     * Ask user-service to deliver {@code subject}/{@code message} to
     * {@code userUuid} over that user's channels. Returns whether user-service
     * ACCEPTED the request (2xx) — acceptance, not delivery, which is async
     * there. Never throws; a null uuid or blank content is a quiet no-op
     * (returns false).
     */
    public boolean notify(UUID userUuid, String subject, String message) {
        if (userUuid == null || subject == null || subject.isBlank()
                || message == null || message.isBlank()) {
            log.debug("Skipping user notify: uuid present={} subject blank={} message blank={}",
                    userUuid != null, subject == null || subject.isBlank(),
                    message == null || message.isBlank());
            return false;
        }
        try {
            restClient.post()
                    .uri("/users/internal/{uuid}/notify", userUuid)
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("subject", subject, "message", message))
                    .retrieve()
                    .toBodilessEntity();
            metrics.notificationOutcome("user_notify", "accepted");
            log.debug("User notify accepted userUuid={}", userUuid);
            return true;
        } catch (RuntimeException e) {
            // Best-effort: a notification failure must never fail the action it
            // accompanies (order paid, restock). Logged + metered, never thrown.
            metrics.notificationOutcome("user_notify", "failed");
            log.warn("User notify failed userUuid={} cause={}", userUuid, e.toString());
            return false;
        }
    }
}
