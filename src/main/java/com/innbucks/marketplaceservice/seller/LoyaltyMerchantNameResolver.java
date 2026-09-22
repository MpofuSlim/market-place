package com.innbucks.marketplaceservice.seller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real {@link MerchantNameResolver}: asks loyalty-service over the S2S
 * {@code GET /loyalty/internal/merchants/names} endpoint, authenticated by the
 * shared {@code X-Internal-Token}.
 *
 * <p>Resolved by service NAME through Eureka on the {@code @LoadBalanced}
 * builder, like every other in-fleet caller here — never a hardcoded
 * host:port. Shape and failure posture are
 * {@code UserServiceMerchantAdminResolver}'s, deliberately: one question, one
 * batched call, and every failure mode collapses to the same empty answer.
 *
 * <h2>The cache, and why it is safe here when it would not be elsewhere</h2>
 * A trading name is the ONE thing worth caching in front of another service,
 * and the argument is entirely about scope: nothing in this service DECIDES on
 * a name. Ownership comes from our own {@code merchant_id} columns, money from
 * the settlement ledger, a seller's standing from {@code marketplace_seller}.
 * A cached BALANCE or a cached OWNERSHIP would be a correctness bug no TTL
 * makes safe; a cached name is a label that is at worst a few minutes stale.
 * (Same reasoning, and the same narrow licence, as the middleware's
 * {@code CustomerNameResolver}.) <b>Do not widen this into a general cache in
 * front of loyalty.</b>
 *
 * <p>Two properties hold it up:
 * <ul>
 *   <li><b>Successes only.</b> A failed or absent lookup is never cached, so a
 *       loyalty blip cannot pin every merchant as nameless for a whole TTL —
 *       the next page retries. The cost is that a sustained outage re-asks,
 *       which is the right way round for something this cheap to fail.</li>
 *   <li><b>The TTL is a staleness budget</b>: the worst-case delay before a
 *       rename in the registry reaches a shopper. This service has no
 *       name-write path of its own, so expiry is the complete invalidation
 *       story. <b>If one is ever added here, it must evict.</b></li>
 * </ul>
 */
@Slf4j
@Component
public class LoyaltyMerchantNameResolver implements MerchantNameResolver {

    /** Loyalty's own ceiling on one lookup. Larger asks are chunked. */
    private static final int MAX_IDS_PER_CALL = 200;

    private final RestClient restClient;
    private final String internalToken;
    private final Duration ttl;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    public LoyaltyMerchantNameResolver(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${loyalty-service.base-url:http://loyalty-service}") String loyaltyBaseUrl,
            @Value("${loyalty-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${loyalty-service.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${marketplace.merchant-names.ttl-seconds:300}") long ttlSeconds,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.clone()
                .baseUrl(loyaltyBaseUrl)
                .requestFactory(factory)
                .build();
        this.internalToken = internalToken;
        this.ttl = Duration.ofSeconds(Math.max(ttlSeconds, 0));
    }

    @Override
    public Map<UUID, String> namesFor(Collection<UUID> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return Map.of();
        }
        Instant now = Instant.now();
        Map<UUID, String> resolved = new LinkedHashMap<>();
        List<UUID> misses = new ArrayList<>();
        for (UUID id : merchantIds) {
            if (id == null || resolved.containsKey(id) || misses.contains(id)) {
                continue;
            }
            Cached hit = cache.get(id);
            if (hit != null && hit.expiresAt().isAfter(now)) {
                resolved.put(id, hit.name());
            } else {
                misses.add(id);
            }
        }
        if (misses.isEmpty()) {
            return Map.copyOf(resolved);
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Skipping merchant-name lookup; INTERNAL_API_TOKEN is not configured");
            return Map.copyOf(resolved);
        }
        // Chunked to loyalty's own cap: a payout run or a wide catalogue page
        // can legitimately name more merchants than one call accepts, and
        // splitting is strictly better than being refused.
        for (int from = 0; from < misses.size(); from += MAX_IDS_PER_CALL) {
            List<UUID> chunk = misses.subList(from, Math.min(from + MAX_IDS_PER_CALL, misses.size()));
            resolved.putAll(fetch(chunk));
        }
        return Map.copyOf(resolved);
    }

    private Map<UUID, String> fetch(List<UUID> ids) {
        try {
            String joined = String.join(",", ids.stream().map(UUID::toString).toList());
            NamesEnvelope body = restClient.get()
                    .uri(uri -> uri.path("/loyalty/internal/merchants/names")
                            .queryParam("ids", joined).build())
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    // Swallowed rather than thrown: 401 (token drift), 404 (a
                    // loyalty too old to serve this yet) and 5xx all mean the
                    // same thing here — no names, render none.
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .body(NamesEnvelope.class);
            return absorb(body);
        } catch (Exception ex) {
            log.warn("Merchant-name lookup failed for {} id(s) cause={}", ids.size(), ex.toString());
            return Map.of();
        }
    }

    /** Reads the rows we understand and caches only what actually resolved. */
    private Map<UUID, String> absorb(NamesEnvelope body) {
        if (body == null || body.merchants() == null || body.merchants().isEmpty()) {
            return Map.of();
        }
        Instant expiresAt = Instant.now().plus(ttl);
        Map<UUID, String> out = new LinkedHashMap<>();
        for (MerchantName row : body.merchants()) {
            if (row == null || row.merchantId() == null || row.merchantId().isBlank()) {
                continue;
            }
            String name = row.name() == null || row.name().isBlank() ? null : row.name().trim();
            if (name == null) {
                // Defensive only: loyalty's merchants.name is NOT NULL, so a
                // known merchant always carries one and a missing row means
                // the id names nothing. Should that ever change, a blank is
                // nothing to render and nothing to cache — a name added later
                // should appear on the next page, not after a TTL.
                continue;
            }
            try {
                UUID id = UUID.fromString(row.merchantId().trim());
                out.put(id, name);
                cache.put(id, new Cached(name, expiresAt));
            } catch (IllegalArgumentException ignored) {
                log.warn("Unparseable merchantId in names response — skipped");
            }
        }
        return out;
    }

    private record Cached(String name, Instant expiresAt) {
    }

    /** Loyalty's internal surface serves PLAIN maps, not the ApiResult envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NamesEnvelope(List<MerchantName> merchants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MerchantName(String merchantId, String name) {
    }
}
