package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.config.OutboundHttp;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
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
 * <p>Resolved by service NAME via the discovery map ({@code http://user-service} on
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

    public UserNotifyGateway(OutboundHttp outboundHttp,
                             @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
                             @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
                             @Value("${user-service.connect-timeout-ms:2000}") int connectTimeoutMs,
                             @Value("${user-service.read-timeout-ms:5000}") int readTimeoutMs,
                             @Value("${innbucks.internal-api-token:}") String internalToken,
                             MarketplaceMetrics metrics) {
        this.restClient = builder.clone()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(outboundHttp.requestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
        this.internalToken = internalToken;
        this.metrics = metrics;
    }

    /**
     * What became of one notify request. The boolean {@link #notify} methods
     * collapse it to "accepted or not"; the restock fan-out needs the split,
     * because only {@link #UNAVAILABLE} says anything about user-service's
     * health — a 404 for one vanished user is user-service answering fine.
     */
    public enum Delivery {
        /** 2xx: user-service queued it (delivery itself is async there). */
        ACCEPTED,
        /** A 4xx other than 429: user-service answered and declined THIS
         *  request (unknown user, bad token). Not a health signal. */
        REFUSED,
        /** No usable answer: connect refused, timeout, reset, 5xx or 429. The
         *  only outcome a circuit breaker counts as a failure. */
        UNAVAILABLE,
        /** Blank input — nothing was sent. */
        SKIPPED,
        /** Never returned by this gateway: a {@link FanoutCircuitBreaker} that
         *  was open and did not call it at all. */
        SHORT_CIRCUITED
    }

    /**
     * Ask user-service to deliver {@code subject}/{@code message} to
     * {@code userUuid} over that user's channels. Returns whether user-service
     * ACCEPTED the request (2xx) — acceptance, not delivery, which is async
     * there. Never throws; a null uuid or blank content is a quiet no-op
     * (returns false).
     */
    public boolean notify(UUID userUuid, String subject, String message) {
        return notify(userUuid, UserNotice.plain(subject, message));
    }

    /**
     * Sends a notice with whatever of type / severity / subject / deep link it
     * carries. Absent fields are left OUT of the body rather than sent as
     * null, so the wire shape for a plain notice is exactly what it always was.
     */
    public boolean notify(UUID userUuid, UserNotice notice) {
        return deliver(userUuid, notice) == Delivery.ACCEPTED;
    }

    /** {@link #deliver(UUID, UserNotice)} for a plain subject + message. */
    public Delivery deliver(UUID userUuid, String subject, String message) {
        return deliver(userUuid, UserNotice.plain(subject, message));
    }

    /**
     * The same request as {@link #notify(UUID, UserNotice)}, reporting WHY it
     * was not accepted. Never throws, never returns null, never returns
     * {@link Delivery#SHORT_CIRCUITED}. Metrics and logging are identical to
     * {@code notify} ({@code user_notify} {@code accepted|failed}).
     */
    public Delivery deliver(UUID userUuid, UserNotice notice) {
        String subject = notice == null ? null : notice.subject();
        String message = notice == null ? null : notice.message();
        if (userUuid == null || subject == null || subject.isBlank()
                || message == null || message.isBlank()) {
            log.debug("Skipping user notify: uuid present={} subject blank={} message blank={}",
                    userUuid != null, subject == null || subject.isBlank(),
                    message == null || message.isBlank());
            return Delivery.SKIPPED;
        }
        try {
            restClient.post()
                    .uri("/users/internal/{uuid}/notify", userUuid)
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(notice))
                    .retrieve()
                    .toBodilessEntity();
            metrics.notificationOutcome("user_notify", "accepted");
            log.debug("User notify accepted userUuid={}", userUuid);
            return Delivery.ACCEPTED;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            return failed(userUuid, e, status >= 500 || status == 429 ? Delivery.UNAVAILABLE : Delivery.REFUSED);
        } catch (RuntimeException e) {
            // Connect refused, read timeout, reset: no answer at all.
            return failed(userUuid, e, Delivery.UNAVAILABLE);
        }
    }

    private Delivery failed(UUID userUuid, RuntimeException e, Delivery delivery) {
        // Best-effort: a notification failure must never fail the action it
        // accompanies (order paid, restock). Logged + metered, never thrown.
        metrics.notificationOutcome("user_notify", "failed");
        log.warn("User notify failed userUuid={} outcome={} cause={}", userUuid, delivery, e.toString());
        return delivery;
    }

    private static Map<String, String> body(UserNotice notice) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("subject", notice.subject());
        body.put("message", notice.message());
        putIfPresent(body, "type", notice.type());
        putIfPresent(body, "severity", notice.severity());
        putIfPresent(body, "subjectKind", notice.subjectKind());
        putIfPresent(body, "subjectId", notice.subjectId());
        putIfPresent(body, "deepLink", notice.deepLink());
        return body;
    }

    private static void putIfPresent(Map<String, String> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }
}
