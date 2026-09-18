package com.innbucks.marketplaceservice.publictest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Boot-time announcement for the {@code /marketplace/public/**} test surface.
 *
 * <p>It answers one question an operator cannot answer any other way without a
 * live request: <b>is this cell serving an unauthenticated buyer surface right
 * now, and is anything in front of it?</b> Silent when the surface is off, so a
 * cell that never enables it is untouched.
 *
 * <p>ERROR — not a boot failure — when the surface is on under a deployment
 * profile. A build aid must never stop a cell starting, but a production cell
 * serving this deserves the loudest line the log has. The profile test is the
 * same one {@code ProductionSecretsGuard} uses: "deployment" means an active
 * profile set with no {@code dev}/{@code test}/{@code it}/{@code local} in it,
 * the empty set included.
 */
@Component
@Slf4j
public class PublicTestProvisioningCheck {

    private static final List<String> NON_DEPLOYMENT_PROFILES = List.of("dev", "test", "it", "local");

    private final boolean enabled;
    private final String apiKey;
    private final Environment environment;

    public PublicTestProvisioningCheck(
            @Value("${marketplace.public-test.enabled:false}") boolean enabled,
            @Value("${marketplace.public-test.api-key:}") String apiKey,
            Environment environment) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkPublicTestProvisioning() {
        if (!enabled) return;

        if (isDeploymentProfile()) {
            log.error("Public test surface is ENABLED ON A DEPLOYMENT PROFILE: /marketplace/public/** "
                    + "serves carts, address books, wishlists and checkout quotes with NO "
                    + "authentication. This is a build aid for staging only. Set "
                    + "MARKETPLACE_PUBLIC_TEST_ENABLED=false unless this cell is deliberately a test "
                    + "environment.");
        }

        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            log.warn("Public test surface is UNGATED: /marketplace/public/** is ENABLED and "
                    + "MARKETPLACE_PUBLIC_TEST_API_KEY is blank, so any caller who can reach this cell "
                    + "can use it. To gate it, set the key (openssl rand -base64 32) in this host's "
                    + "gitignored cell.<iso>.local.env and give the same value to the app.");
        } else {
            log.info("Public test surface is enabled and gated by an x-api-key.");
        }

        log.info("Public test surface serves PRE-CHECKOUT endpoints only: cart, addresses, favorites "
                + "and checkout quote. It cannot create an order, take a payment or send a message.");
    }

    /** Mirrors ProductionSecretsGuard: no dev/test/it/local profile — the empty set included. */
    private boolean isDeploymentProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .noneMatch(p -> NON_DEPLOYMENT_PROFILES.contains(p.toLowerCase()));
    }
}
