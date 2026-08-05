package com.innbucks.marketplaceservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link ProductionSecretsGuard}'s fail-closed deployment semantics
 * (fleet A02-M3): "deployment" = an active-profile set with NO
 * dev/test/it/local profile, INCLUDING the empty set. Uses a
 * {@link MockEnvironment} — no Spring context.
 */
class ProductionSecretsGuardTest {

    private static final String VALID_JWT_SECRET =
            "jwt-secret-unit-0123456789abcdefghijklmnopqrstuv";
    private static final String VALID_INTERNAL_TOKEN =
            "internal-token-unit-0123456789abcdefghijklmnopqr";
    private static final String VALID_AUDIT_SECRET =
            "audit-hmac-unit-0123456789abcdefghijklmnopqrstuv";

    // The exact defaults application.yaml ships.
    private static final String PLACEHOLDER_JWT = "change-me-change-me-change-me-change-me";
    private static final String PLACEHOLDER_INTERNAL =
            "change-me-internal-token-change-me-internal-token";
    private static final String PLACEHOLDER_AUDIT =
            "change-me-audit-hmac-secret-change-me-audit";

    private static MockEnvironment fullyProvisioned() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("jwt.secret", VALID_JWT_SECRET);
        env.setProperty("innbucks.internal-api-token", VALID_INTERNAL_TOKEN);
        env.setProperty("marketplace.audit.hmac-secret", VALID_AUDIT_SECRET);
        env.setProperty("spring.datasource.password", "db-password");
        env.setProperty("spring.data.redis.password", "redis-password");
        return env;
    }

    private static MockEnvironment placeholders() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("jwt.secret", PLACEHOLDER_JWT);
        env.setProperty("innbucks.internal-api-token", PLACEHOLDER_INTERNAL);
        env.setProperty("marketplace.audit.hmac-secret", PLACEHOLDER_AUDIT);
        env.setProperty("spring.datasource.password", "db-password");
        env.setProperty("spring.data.redis.password", "redis-password");
        return env;
    }

    private static void verify(MockEnvironment env) {
        new ProductionSecretsGuard(env).verifyNoPlaceholderSecrets();
    }

    @Test
    void emptyProfileSetIsADeploymentAndRefusesPlaceholders() {
        // MockEnvironment starts with NO active profiles — the A02-M3 case: a
        // container launched without SPRING_PROFILES_ACTIVE must fail closed.
        assertThatThrownBy(() -> verify(placeholders()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void emptyProfileSetWithRealSecretsPasses() {
        assertThatCode(() -> verify(fullyProvisioned())).doesNotThrowAnyException();
    }

    @Test
    void prodProfileWithRealSecretsPasses() {
        MockEnvironment env = fullyProvisioned();
        env.setActiveProfiles("prod");
        assertThatCode(() -> verify(env)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "it", "local"})
    void nonDeploymentProfilesBootOnPlaceholders(String profile) {
        MockEnvironment env = placeholders();
        env.setActiveProfiles(profile);
        assertThatCode(() -> verify(env)).doesNotThrowAnyException();
    }

    @Test
    void anyNonDeploymentProfileInTheSetSkipsTheGuard() {
        // {"staging","dev"} contains a non-deployment profile → not a deployment.
        MockEnvironment env = placeholders();
        env.setActiveProfiles("staging", "dev");
        assertThatCode(() -> verify(env)).doesNotThrowAnyException();
    }

    @Test
    void deploymentProfileRefusesShortSecret() {
        MockEnvironment env = fullyProvisioned();
        env.setActiveProfiles("prod");
        env.setProperty("jwt.secret", "way-too-short");
        assertThatThrownBy(() -> verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("too short");
    }

    @Test
    void deploymentProfileRefusesPlaceholderMarkerInsideLongValue() {
        // Long enough, but carrying a marker mid-value — still refused.
        MockEnvironment env = fullyProvisioned();
        env.setActiveProfiles("prod");
        env.setProperty("marketplace.audit.hmac-secret",
                "x".repeat(30) + "-placeholder-" + "y".repeat(30));
        assertThatThrownBy(() -> verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace.audit.hmac-secret")
                .hasMessageContaining("placeholder value");
    }

    @Test
    void deploymentProfileRefusesEqualSecrets() {
        MockEnvironment env = fullyProvisioned();
        env.setActiveProfiles("prod");
        env.setProperty("innbucks.internal-api-token", VALID_JWT_SECRET);
        assertThatThrownBy(() -> verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secrets must be distinct");
    }

    @Test
    void deploymentProfileRefusesMissingSecret() {
        // MockEnvironment has no application.yaml defaults, so an unset key
        // resolves null — the "env var never provisioned" shape.
        MockEnvironment withoutJwt = new MockEnvironment();
        withoutJwt.setActiveProfiles("prod");
        withoutJwt.setProperty("innbucks.internal-api-token", VALID_INTERNAL_TOKEN);
        withoutJwt.setProperty("marketplace.audit.hmac-secret", VALID_AUDIT_SECRET);
        withoutJwt.setProperty("spring.datasource.password", "db-password");
        withoutJwt.setProperty("spring.data.redis.password", "redis-password");
        assertThatThrownBy(() -> verify(withoutJwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("blank/missing");
    }

    @Test
    void deploymentProfileRefusesBlankDbPassword() {
        MockEnvironment env = fullyProvisioned();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    void deploymentProfileRefusesBlankRedisPassword() {
        MockEnvironment env = fullyProvisioned();
        env.setActiveProfiles("prod");
        env.setProperty("spring.data.redis.password", "");
        assertThatThrownBy(() -> verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.password");
    }
}
