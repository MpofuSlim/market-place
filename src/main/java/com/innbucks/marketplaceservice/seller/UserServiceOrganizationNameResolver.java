package com.innbucks.marketplaceservice.seller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.innbucks.marketplaceservice.config.OutboundHttp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real {@link MerchantNameResolver}: asks user-service over the S2S
 * {@code GET /users/internal/organizations/names} endpoint, authenticated by the
 * shared {@code X-Internal-Token}.
 *
 * <p>A seller's {@code merchantId} is the id of the ORGANIZATION that sells
 * (user-service V39), so its trading name is the organization's name, and
 * user-service is the registry. This used to ask loyalty for a loyalty
 * merchant's name, back when the seller id was a loyalty merchant id copied off
 * a claim user-service no longer mints.
 *
 * <p>Resolved by service NAME (the k8s Service, via the discovery map) on the {@code @LoadBalanced}
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
 * front of user-service.</b>
 *
 * <p>Two properties hold it up:
 * <ul>
 *   <li><b>Successes only.</b> A failed or absent lookup is never cached AS A
 *       NAME, so a user-service blip cannot pin every merchant as nameless for
 *       a whole TTL.</li>
 *   <li><b>The TTL is a staleness budget</b>: the worst-case delay before a
 *       rename in the registry reaches a shopper. This service has no
 *       name-write path of its own, so expiry is the complete invalidation
 *       story. <b>If one is ever added here, it must evict.</b></li>
 * </ul>
 *
 * <h2>What a slow or dead user-service is allowed to cost — almost nothing</h2>
 * This sits on the PUBLIC catalogue, so its latency is every shopper's latency.
 * Three rules keep it from ever becoming an outage of this service:
 * <ul>
 *   <li><b>Never inside a transaction.</b> A lookup made while a transaction is
 *       active answers from the cache and does not touch the network: the
 *       transaction holds a pooled connection (and, on a write path, row
 *       locks), and holding either across somebody else's HTTP call is how a
 *       user-service stall became pool exhaustion for orders and payment
 *       confirms. The reads that render names resolve them after their
 *       queries have committed; see {@code NameLookupHoldsNoConnectionIT}.</li>
 *   <li><b>Its own tight timeouts</b> ({@code marketplace.merchant-names.
 *       connect-timeout-ms} / {@code read-timeout-ms}), not the shared
 *       {@code user-service.*} ones sized for notifications: a label is not
 *       worth seconds of a shopper's page.</li>
 *   <li><b>A short backoff after a failure</b>
 *       ({@code marketplace.merchant-names.failure-backoff-seconds}): once a
 *       lookup fails, every page skips the call for that window and renders
 *       without the missing names, instead of each one paying the timeout
 *       again. When the window lapses exactly ONE caller probes; the rest keep
 *       skipping until it answers. This is a backoff on the CALL, not a cached
 *       "no name" — a success clears it at once.</li>
 * </ul>
 * Watch {@code marketplace.merchant_names{outcome}}: {@code failed} is
 * user-service answering badly, {@code backoff} is pages rendered without
 * asking because of it, and {@code in_transaction} counts renders served
 * cache-only because a transaction was open. That one is NEVER flat: write
 * paths that answer with seller names (a listing edit, an order action) land
 * there on every cold-cache miss by design. The signal is a jump in its RATE
 * relative to {@code resolved} — a read that should fetch names has been made
 * transactional again (which {@code MerchantNameResolutionTest} also pins).
 */
@Slf4j
@Component
public class UserServiceOrganizationNameResolver implements MerchantNameResolver {

    /** user-service's own ceiling on one lookup. Larger asks are chunked. */
    private static final int MAX_IDS_PER_CALL = 200;

    /** {@link #backoffUntilMillis} value meaning "healthy, no backoff". */
    private static final long HEALTHY = 0L;

    private final RestClient restClient;
    private final String internalToken;
    private final Duration ttl;
    private final long backoffMillis;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Epoch millis until which no call is made, or {@link #HEALTHY}. A single
     * atomic so the half-open probe is claimed by exactly one thread.
     */
    private final AtomicLong backoffUntilMillis = new AtomicLong(HEALTHY);

    @Autowired
    public UserServiceOrganizationNameResolver(
            OutboundHttp outboundHttp,
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${marketplace.merchant-names.connect-timeout-ms:500}") int connectTimeoutMs,
            @Value("${marketplace.merchant-names.read-timeout-ms:1000}") int readTimeoutMs,
            @Value("${marketplace.merchant-names.ttl-seconds:300}") long ttlSeconds,
            @Value("${marketplace.merchant-names.failure-backoff-seconds:30}") long failureBackoffSeconds,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            MeterRegistry meterRegistry) {
        this(outboundHttp, builder, userServiceBaseUrl, connectTimeoutMs, readTimeoutMs, ttlSeconds,
                failureBackoffSeconds, internalToken, meterRegistry, Clock.systemUTC());
    }

    /** Full constructor; tests pass a controllable clock. */
    UserServiceOrganizationNameResolver(OutboundHttp outboundHttp,
                                        RestClient.Builder builder,
                                        String userServiceBaseUrl,
                                        int connectTimeoutMs,
                                        int readTimeoutMs,
                                        long ttlSeconds,
                                        long failureBackoffSeconds,
                                        String internalToken,
                                        MeterRegistry meterRegistry,
                                        Clock clock) {
        this.restClient = builder.clone()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(outboundHttp.requestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
        this.internalToken = internalToken;
        this.ttl = Duration.ofSeconds(Math.max(ttlSeconds, 0));
        this.backoffMillis = Duration.ofSeconds(Math.max(failureBackoffSeconds, 0)).toMillis();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public Map<UUID, String> namesFor(Collection<UUID> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
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
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Never hold a pooled connection (or a write path's row locks)
            // across user-service's latency. What the cache has is what this
            // render gets; the reads that follow outside a transaction fill it.
            count("in_transaction");
            log.debug("Merchant-name lookup skipped inside a transaction for {} id(s)", misses.size());
            return Map.copyOf(resolved);
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Skipping merchant-name lookup; INTERNAL_API_TOKEN is not configured");
            return Map.copyOf(resolved);
        }
        if (!mayCall(now.toEpochMilli())) {
            count("backoff");
            return Map.copyOf(resolved);
        }
        // Chunked to user-service's own cap: a payout run or a wide catalogue page
        // can legitimately name more merchants than one call accepts, and
        // splitting is strictly better than being refused.
        for (int from = 0; from < misses.size(); from += MAX_IDS_PER_CALL) {
            List<UUID> chunk = misses.subList(from, Math.min(from + MAX_IDS_PER_CALL, misses.size()));
            Map<UUID, String> fetched = fetch(chunk);
            if (fetched == null) {
                // One failure stops the rest of the ask too: the next chunk
                // would only pay the same timeout for the same answer.
                backoffUntilMillis.set(clock.millis() + backoffMillis);
                count("failed");
                return Map.copyOf(resolved);
            }
            resolved.putAll(fetched);
        }
        backoffUntilMillis.set(HEALTHY);
        count("resolved");
        return Map.copyOf(resolved);
    }

    /**
     * Whether this caller may go to the network now. Healthy: always. Backing
     * off: not until the window lapses, and then only the ONE caller whose
     * compare-and-set claims the probe — it pushes the window out by a full
     * backoff while it asks, so a burst of pages arriving the moment the window
     * ends does not become a burst of calls at a service that was failing.
     */
    private boolean mayCall(long nowMillis) {
        long until = backoffUntilMillis.get();
        if (until == HEALTHY) {
            return true;
        }
        if (nowMillis < until) {
            return false;
        }
        return backoffUntilMillis.compareAndSet(until, nowMillis + backoffMillis);
    }

    /**
     * One call. Returns the names it resolved (possibly none — an id the
     * registry does not know is simply absent), or {@code null} when the call
     * FAILED: an exception or timeout, a non-2xx status, or a 2xx body that is
     * not the agreed envelope. Only a failure opens the backoff; a well-formed
     * answer naming nobody is a success.
     */
    private Map<UUID, String> fetch(List<UUID> ids) {
        try {
            String joined = String.join(",", ids.stream().map(UUID::toString).toList());
            NamesEnvelope body = restClient.get()
                    .uri(uri -> uri.path("/users/internal/organizations/names")
                            .queryParam("ids", joined).build())
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    // 401 (token drift), 404 (a user-service without the
                    // organization surface) and 5xx all mean the same thing
                    // here — no names, render none — and all back off.
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new LookupFailed("HTTP " + res.getStatusCode().value());
                    })
                    .body(NamesEnvelope.class);
            if (body == null || body.data() == null) {
                throw new LookupFailed("response is not the names envelope");
            }
            return absorb(body);
        } catch (Exception ex) {
            log.warn("Merchant-name lookup failed for {} id(s); skipping lookups for {}s cause={}",
                    ids.size(), backoffMillis / 1000, ex.toString());
            return null;
        }
    }

    /** Reads the rows we understand and caches only what actually resolved. */
    private Map<UUID, String> absorb(NamesEnvelope body) {
        if (body.data().isEmpty()) {
            return Map.of();
        }
        Instant expiresAt = clock.instant().plus(ttl);
        Map<UUID, String> out = new LinkedHashMap<>();
        for (OrganizationName row : body.data()) {
            if (row == null || row.organizationId() == null || row.organizationId().isBlank()) {
                continue;
            }
            String name = row.name() == null || row.name().isBlank() ? null : row.name().trim();
            if (name == null) {
                // Defensive only: organizations.name is NOT NULL, so a known
                // organization always carries one and a missing row means the
                // id names nothing. Should that ever change, a blank is
                // nothing to render and nothing to cache — a name added later
                // should appear on the next page, not after a TTL.
                continue;
            }
            try {
                UUID id = UUID.fromString(row.organizationId().trim());
                out.put(id, name);
                cache.put(id, new Cached(name, expiresAt));
            } catch (IllegalArgumentException ignored) {
                log.warn("Unparseable organizationId in names response — skipped");
            }
        }
        return out;
    }

    private void count(String outcome) {
        Counter.builder("marketplace.merchant_names")
                .description("Seller-name lookups against user-service by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** A non-2xx answer or a body that is not the envelope — a failure the
     *  backoff counts, carried out of the status handler to the one catch. */
    private static final class LookupFailed extends RuntimeException {
        LookupFailed(String message) {
            super(message, null, false, false);
        }
    }

    private record Cached(String name, Instant expiresAt) {
    }

    /** user-service's standard {@code ApiResult} envelope, trimmed to what we read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NamesEnvelope(List<OrganizationName> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrganizationName(String organizationId, String name) {
    }
}
