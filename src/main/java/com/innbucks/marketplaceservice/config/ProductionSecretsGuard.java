package com.innbucks.marketplaceservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to boot under a real DEPLOYMENT profile if any sensitive secret still
 * holds a placeholder default from {@code application.yaml}, is too short to be
 * a real secret, or duplicates another secret. It's far too easy to deploy a
 * service with the JWT or HMAC secret unchanged; this fails fast at startup so
 * that mistake never reaches the wire.
 *
 * <p>"Deployment" = an active-profile set containing NO {@code dev/test/it/local}
 * profile (e.g. {@code prod}, {@code uat}, {@code staging}) — <b>including the
 * EMPTY set</b> (fleet A02-M3): a container launched without
 * {@code SPRING_PROFILES_ACTIVE} is treated as a deployment and fails closed on
 * placeholder secrets instead of silently booting on them. Local dev / a stray
 * {@code java -jar} must opt out explicitly with a dev/test/local profile.
 * Stronger than gating on {@code prod} alone: a staging box, or a manifest that
 * sets some non-prod deployment profile, is covered too.
 */
@Configuration
@Slf4j
public class ProductionSecretsGuard {

    // Add to this list when new sensitive properties land. Keep it tight —
    // only secrets whose disclosure would compromise the service, not every
    // tunable.
    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret",
            "innbucks.internal-api-token",
            "marketplace.audit.hmac-secret"
    );

    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me", "changeme", "placeholder", "dev-only"
    );

    // Placeholder secrets are tolerated only under these (local dev + test) profiles.
    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");

    // HS256 requires a >= 32-byte key (Keys.hmacShaKeyFor throws below that),
    // and an HMAC/internal-token secret shorter than this is guessable anyway;
    // fail fast at boot rather than at the first token verification.
    private static final int MIN_SECRET_LENGTH = 32;

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verifyNoPlaceholderSecrets() {
        String[] active = env.getActiveProfiles();
        boolean deployment = Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) {
            log.info("Secrets guard skipped (non-deployment profile: {})", Arrays.toString(active));
            return;
        }

        List<String> offenders = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : SECRETS_TO_CHECK) {
            String value = safeProperty(key);
            values.put(key, value);
            if (value == null || value.isBlank()) {
                offenders.add(key + " (blank/missing)");
            } else if (value.length() < MIN_SECRET_LENGTH) {
                offenders.add(key + " (too short: needs >= " + MIN_SECRET_LENGTH + " chars)");
            } else if (containsPlaceholderMarker(value)) {
                offenders.add(key + " (placeholder value)");
            }
        }

        // All distinct: a shared value means one leaked secret compromises every
        // control keyed on it (e.g. jwt.secret == audit hmac lets a token forger
        // also re-seal tampered audit rows). Blank values are already flagged
        // above, so only compare real candidates.
        for (int i = 0; i < SECRETS_TO_CHECK.size(); i++) {
            for (int j = i + 1; j < SECRETS_TO_CHECK.size(); j++) {
                String a = values.get(SECRETS_TO_CHECK.get(i));
                String b = values.get(SECRETS_TO_CHECK.get(j));
                if (a != null && !a.isBlank() && b != null && !b.isBlank()
                        && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                                                 b.getBytes(StandardCharsets.UTF_8))) {
                    offenders.add(SECRETS_TO_CHECK.get(i) + " == " + SECRETS_TO_CHECK.get(j)
                            + " (secrets must be distinct)");
                }
            }
        }

        // The DB password guards the audit chain's storage and the Redis password
        // guards the shared logout-denylist state — a blank either one under a
        // deployment profile is an unauthenticated tamper surface. compose/k8s
        // already provide both; this makes a forgotten one fail fast.
        requireNonBlank("spring.datasource.password", "DB_PASSWORD", offenders);
        requireNonBlank("spring.data.redis.password", "REDIS_PASSWORD", offenders);

        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under deployment profile " + Arrays.toString(active) +
                    ": these secrets still hold placeholder/weak values: " + offenders +
                    ". Override them via env vars (JWT_SECRET, INTERNAL_API_TOKEN, " +
                    "AUDIT_HMAC_SECRET, DB_PASSWORD, REDIS_PASSWORD) before deploying — " +
                    "generate each with `openssl rand -base64 48`."
            );
        }
        log.info("Secrets guard passed for profile {} ({} keys verified)",
                Arrays.toString(active), SECRETS_TO_CHECK.size());
    }

    private void requireNonBlank(String key, String envVar, List<String> offenders) {
        String value = safeProperty(key);
        if (value == null || value.isBlank()) {
            offenders.add(key + " (blank — set " + envVar + " under deployment)");
        }
    }

    /**
     * {@code spring.datasource.password} is {@code ${DB_PASSWORD}} with no
     * default, so resolving it with the env var unset throws — map that to
     * "missing" so the guard reports the offender instead of a raw resolution
     * error.
     */
    private String safeProperty(String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    private static boolean containsPlaceholderMarker(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
    }
}
