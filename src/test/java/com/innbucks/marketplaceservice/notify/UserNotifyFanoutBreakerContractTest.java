package com.innbucks.marketplaceservice.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway.Delivery;
import com.innbucks.marketplaceservice.testsupport.TestOutboundHttp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The restock fan-out's wire behaviour against user-service's
 * {@code POST /users/internal/{uuid}/notify} when user-service fails:
 * {@link UserNotifyGateway} driven through a {@link FanoutCircuitBreaker}, the
 * pair {@code RestockAlertListener} uses. Pins what reaches the WIRE — the
 * breaker opening stops requests, half-open lets exactly the trial calls
 * through, and only no-answer / 5xx / 429 / slow calls count against it.
 * Pure JUnit + WireMock; the open-state wait is driven by a hand-moved clock.
 */
class UserNotifyFanoutBreakerContractTest {

    private static final String TOKEN = "test-internal-token";
    private static final String NOTIFY = "/users/internal/[0-9a-f-]+/notify";

    private static WireMockServer wireMock;

    private SimpleMeterRegistry registry;
    private MutableClock clock;
    private FanoutCircuitBreaker.Settings settings;

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
    void setUp() {
        registry = new SimpleMeterRegistry();
        clock = new MutableClock();
        settings = new FanoutCircuitBreaker.Settings(); // production defaults
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private UserNotifyGateway gateway(String baseUrl) {
        return new UserNotifyGateway(TestOutboundHttp.POOL, RestClient.builder(), baseUrl, 500, 2000, TOKEN,
                new MarketplaceMetrics(registry));
    }

    private UserNotifyGateway gateway() {
        return gateway("http://localhost:" + wireMock.port());
    }

    private FanoutCircuitBreaker breaker() {
        return new FanoutCircuitBreaker("restock_alert_user_notify", settings, clock, registry);
    }

    /** One fan-out of {@code recipients} sends; returns how many were short-circuited. */
    private static int fanOut(FanoutCircuitBreaker breaker, UserNotifyGateway gateway, int recipients) {
        int shortCircuited = 0;
        for (int i = 0; i < recipients; i++) {
            UUID user = UUID.randomUUID();
            if (breaker.call(() -> gateway.deliver(user, "Back in stock on InnBucks Marketplace",
                    "Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace"))
                    == Delivery.SHORT_CIRCUITED) {
                shortCircuited++;
            }
        }
        return shortCircuited;
    }

    private static void stubStatus(int status) {
        wireMock.stubFor(post(urlPathMatching(NOTIFY)).willReturn(aResponse().withStatus(status)));
    }

    private static int wireCalls() {
        return wireMock.countRequestsMatching(postRequestedFor(urlPathMatching(NOTIFY)).build()).getCount();
    }

    @Test
    @DisplayName("user-service 503: 5 calls reach the wire, the breaker opens, the other 195 never do; "
            + "after the wait two trial calls go out, 202s close it, and the wire contract is unchanged")
    void serverErrors_openTheBreaker_thenHalfOpenAndClose() {
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        stubStatus(503);

        assertThat(fanOut(breaker, gateway, 200)).isEqualTo(195);
        assertThat(wireCalls()).isEqualTo(5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Inside the 30 s wait nothing reaches user-service.
        clock.advance(Duration.ofSeconds(29));
        assertThat(fanOut(breaker, gateway, 50)).isEqualTo(50);
        assertThat(wireCalls()).isEqualTo(5);

        // user-service recovers; past the wait the next call half-opens it.
        wireMock.resetAll();
        stubStatus(202);
        clock.advance(Duration.ofSeconds(2));
        assertThat(fanOut(breaker, gateway, 1)).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(fanOut(breaker, gateway, 1)).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(fanOut(breaker, gateway, 10)).isZero();
        assertThat(wireCalls()).isEqualTo(12);
        wireMock.verify(postRequestedFor(urlPathMatching(NOTIFY))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Back in stock on InnBucks Marketplace"))));

        assertThat(registry.get("marketplace.notifications.breaker_transitions")
                .tag("to", "open").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("marketplace.notifications.breaker_transitions")
                .tag("to", "half_open").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("marketplace.notifications.breaker_transitions")
                .tag("to", "closed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("half-open trial still failing: re-opens at once, and the next wait starts over")
    void failedTrial_reopens() {
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        stubStatus(500);
        fanOut(breaker, gateway, 5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofSeconds(31));
        assertThat(fanOut(breaker, gateway, 20)).isEqualTo(18); // the two trials went out
        assertThat(wireCalls()).isEqualTo(7);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.get("marketplace.notifications.breaker_state")
                .tag("breaker", "restock_alert_user_notify").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("connection reset mid-flight counts as a failure and opens the breaker")
    void faults_openTheBreaker() {
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        wireMock.stubFor(post(urlPathMatching(NOTIFY))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(fanOut(breaker, gateway, 100)).isEqualTo(95);
        assertThat(wireCalls()).isEqualTo(5);
    }

    @Test
    @DisplayName("connect refused (separate dead-port gateway): opens after 5, the rest skip without a connect")
    void connectRefused_opensTheBreaker() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        UserNotifyGateway dead = gateway("http://localhost:" + closedPort);
        FanoutCircuitBreaker breaker = breaker();

        assertThat(fanOut(breaker, dead, 200)).isEqualTo(195);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("429 is user-service shedding load: a failure like a 5xx")
    void tooManyRequests_opensTheBreaker() {
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        stubStatus(429);

        assertThat(fanOut(breaker, gateway, 10)).isEqualTo(5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("404 / 401 are user-service answering: every call goes out and the breaker stays closed")
    void clientErrors_neverOpenTheBreaker() {
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        stubStatus(404);
        assertThat(fanOut(breaker, gateway, 20)).isZero();
        stubStatus(401);
        assertThat(fanOut(breaker, gateway, 20)).isZero();

        assertThat(wireCalls()).isEqualTo(40);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a user-service that ACCEPTS but slowly opens the breaker through the slow-call rate")
    void slowAcceptance_opensTheBreaker() {
        settings.setSlowCallDurationThreshold(Duration.ofMillis(100));
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        wireMock.stubFor(post(urlPathMatching(NOTIFY))
                .willReturn(aResponse().withStatus(202).withFixedDelay(250)));

        assertThat(fanOut(breaker, gateway, 10)).isEqualTo(5);
        assertThat(wireCalls()).isEqualTo(5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("blank input sends nothing and is not recorded: it can neither open nor close the breaker")
    void skippedCalls_areNotRecorded() {
        UserNotifyGateway gateway = gateway();
        FanoutCircuitBreaker breaker = breaker();
        for (int i = 0; i < 20; i++) {
            assertThat(breaker.call(() -> gateway.deliver(null, "s", "m"))).isEqualTo(Delivery.SKIPPED);
        }
        wireMock.verify(0, postRequestedFor(urlPathMatching("/users/internal/.*")));
        stubStatus(503);
        // Still a fresh window: five real failures are needed, not one.
        assertThat(fanOut(breaker, gateway, 10)).isEqualTo(5);
    }

    /** A clock the test moves by hand, so the open-state wait is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
