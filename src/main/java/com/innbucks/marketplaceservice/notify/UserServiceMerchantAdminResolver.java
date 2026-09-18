package com.innbucks.marketplaceservice.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The real {@link MerchantAdminResolver}: asks user-service which accounts run
 * a merchant, over the S2S {@code GET /users/internal/merchants/{id}/admins}
 * endpoint authenticated by the shared {@code X-Internal-Token}.
 *
 * <p><b>Why identity is not resolvable here.</b> The marketplace stores no
 * user↔merchant link at all — {@code Listing.merchantId} and
 * {@code MarketOrderItem.merchantId} are loyalty merchant ids copied off a JWT
 * claim. Behind this one call, user-service chains further: a MERCHANT_ADMIN's
 * user row does not name their merchant either (that column is stamped on shop
 * staff only), so it resolves through loyalty's {@code merchants.admin_email}.
 * All of that is deliberately invisible from here — this service asks one
 * question of the service that owns identity and gets {@code userUuid}s back,
 * which is exactly what {@link UserNotifyGateway} addresses.
 *
 * <p><b>Best-effort, and it must be:</b> the only caller is
 * {@link MerchantOrderNotifier}, which runs inside the never-throws
 * after-commit notification listener. Every failure — an unconfigured token, a
 * 4xx, a 5xx, a timeout, a malformed body — is an empty list, logged and
 * metered. A merchant notification that cannot be addressed must cost a
 * message, never the payment confirm it accompanies.
 *
 * <p>Resolved by service NAME through Eureka on the {@code @LoadBalanced}
 * builder, like every other in-fleet caller here — never a hardcoded host:port.
 */
@Slf4j
@Component
public class UserServiceMerchantAdminResolver implements MerchantAdminResolver {

    private final RestClient restClient;
    private final String internalToken;

    public UserServiceMerchantAdminResolver(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${user-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${user-service.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.clone()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(factory)
                .build();
        this.internalToken = internalToken;
    }

    @Override
    public List<UUID> adminUserUuids(UUID merchantId) {
        if (merchantId == null) {
            return List.of();
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Skipping merchant-admin lookup; INTERNAL_API_TOKEN is not configured");
            return List.of();
        }
        try {
            MerchantAdminsEnvelope body = restClient.get()
                    .uri("/users/internal/merchants/{merchantId}/admins", merchantId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    // Swallowed rather than thrown: 401 (token drift), 404 (a
                    // user-service too old to serve this yet) and 5xx all mean
                    // the same thing to the caller — nobody to notify.
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .body(MerchantAdminsEnvelope.class);
            return uuidsOf(body, merchantId);
        } catch (Exception ex) {
            log.warn("Merchant-admin lookup failed merchantId={} cause={}", merchantId, ex.toString());
            return List.of();
        }
    }

    /**
     * An id that will not parse is SKIPPED, not fatal — one bad row must not
     * cost the merchant's other admins their notification. Same convention as
     * user-service's own merchant-id parsing.
     */
    private static List<UUID> uuidsOf(MerchantAdminsEnvelope body, UUID merchantId) {
        if (body == null || body.data() == null || body.data().isEmpty()) {
            log.debug("No admin users resolved for merchantId={}", merchantId);
            return List.of();
        }
        List<UUID> uuids = new ArrayList<>(body.data().size());
        for (MerchantAdmin admin : body.data()) {
            if (admin == null || admin.userUuid() == null || admin.userUuid().isBlank()) {
                continue;
            }
            try {
                uuids.add(UUID.fromString(admin.userUuid().trim()));
            } catch (IllegalArgumentException ignored) {
                log.warn("Unparseable admin userUuid for merchantId={} — skipped", merchantId);
            }
        }
        return List.copyOf(uuids);
    }

    /** user-service's standard {@code ApiResult} envelope, trimmed to what we read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MerchantAdminsEnvelope(List<MerchantAdmin> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MerchantAdmin(String userUuid, String email) {
    }
}
