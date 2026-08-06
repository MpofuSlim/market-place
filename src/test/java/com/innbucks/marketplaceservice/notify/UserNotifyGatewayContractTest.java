package com.innbucks.marketplaceservice.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract test for {@link UserNotifyGateway} against user-service's internal
 * S2S surface {@code POST /users/internal/{uuid}/notify} (event-service's
 * OrganizerNotificationGateway pattern): JSON body {@code {subject, message}},
 * {@code X-Internal-Token} header, 202 on acceptance. The gateway is strictly
 * best-effort — every failure (5xx, connect refused) is swallowed, logged and
 * metered, NEVER thrown. Pure JUnit + WireMock, no Spring context (the
 * production bean rides the @LoadBalanced builder; here a plain builder points
 * the same code at WireMock's port).
 */
class UserNotifyGatewayContractTest {

    private static final String TOKEN = "test-internal-token";
    private static final UUID USER = UUID.fromString("2e5a9c1b-0d47-4b6e-9f21-3c8d5e7a9b1c");
    private static final String NOTIFY_PATH = "/users/internal/" + USER + "/notify";

    private static WireMockServer wireMock;

    private SimpleMeterRegistry registry;
    private UserNotifyGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void wireGateway() {
        registry = new SimpleMeterRegistry();
        gateway = gateway("http://localhost:" + wireMock.port());
    }

    private UserNotifyGateway gateway(String baseUrl) {
        return new UserNotifyGateway(RestClient.builder(), baseUrl, 500, 2000, TOKEN,
                new MarketplaceMetrics(registry));
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "user_notify").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("happy path: 202 → true; posts {subject,message} with X-Internal-Token")
    void notify_postsDocumentedShape() {
        wireMock.stubFor(post(urlEqualTo(NOTIFY_PATH)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"OK\",\"message\":\"Notification queued\",\"data\":null}")));

        boolean accepted = gateway.notify(USER, "Back in stock on InnBucks Marketplace",
                "Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace");

        assertThat(accepted).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo(NOTIFY_PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.subject",
                        equalTo("Back in stock on InnBucks Marketplace")))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo("Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace"))));
        assertThat(outcome("accepted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("user-service 5xx: swallowed (false), metered failed — NEVER thrown")
    void serverError_swallowedNotThrown() {
        wireMock.stubFor(post(urlEqualTo(NOTIFY_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatCode(() -> {
            boolean accepted = gateway.notify(USER, "s", "m");
            assertThat(accepted).isFalse();
        }).doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("404 (user vanished) and 401 (token mismatch): swallowed, false, metered")
    void clientErrors_swallowedNotThrown() {
        wireMock.stubFor(post(urlEqualTo(NOTIFY_PATH)).willReturn(aResponse().withStatus(404)));
        assertThat(gateway.notify(USER, "s", "m")).isFalse();

        wireMock.stubFor(post(urlEqualTo(NOTIFY_PATH)).willReturn(aResponse().withStatus(401)));
        assertThat(gateway.notify(USER, "s", "m")).isFalse();

        assertThat(outcome("failed")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("connection reset mid-flight: swallowed, false")
    void faultMidFlight_swallowed() {
        wireMock.stubFor(post(urlEqualTo(NOTIFY_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatCode(() -> assertThat(gateway.notify(USER, "s", "m")).isFalse())
                .doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("connect refused (separate dead-port gateway): swallowed, false — never thrown")
    void connectRefused_swallowedNotThrown() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        UserNotifyGateway dead = gateway("http://localhost:" + closedPort);

        assertThatCode(() -> assertThat(dead.notify(USER, "s", "m")).isFalse())
                .doesNotThrowAnyException();
        assertThat(outcome("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("null uuid / blank subject / blank message: quiet no-op, zero wire traffic")
    void blankInputs_neverHitTheWire() {
        assertThat(gateway.notify(null, "s", "m")).isFalse();
        assertThat(gateway.notify(USER, " ", "m")).isFalse();
        assertThat(gateway.notify(USER, "s", " ")).isFalse();
        wireMock.verify(0, postRequestedFor(urlPathMatching("/users/internal/.*")));
    }
}
