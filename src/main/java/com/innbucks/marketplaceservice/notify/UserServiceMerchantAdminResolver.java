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
 * The real {@link MerchantAdminResolver}: asks user-service who runs a seller,
 * over the S2S {@code GET /users/internal/organizations/{id}/admins} endpoint
 * authenticated by the shared {@code X-Internal-Token}.
 *
 * <p>A seller's {@code merchantId} is the id of the ORGANIZATION that sells
 * (user-service V39) — the value this service's JWT filter takes from the
 * {@code orgId} claim. So "who runs this seller" is "who is an OWNER or ADMIN
 * of this organization", which user-service answers directly with
 * {@code userUuid}s — exactly what {@link UserNotifyGateway} addresses. It used
 * to be a three-hop chain through loyalty's {@code merchants.admin_email}, and
 * reached one person at most; a colleague added to the business now gets the
 * next paid-order notification too.
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
                    .uri("/users/internal/organizations/{organizationId}/admins", merchantId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    // Swallowed rather than thrown: 401 (token drift), 404 (a
                    // user-service without the organization surface) and 5xx
                    // all mean the same thing to the caller — nobody to notify.
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
