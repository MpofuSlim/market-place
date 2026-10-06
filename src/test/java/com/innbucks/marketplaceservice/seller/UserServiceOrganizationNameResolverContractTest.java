package com.innbucks.marketplaceservice.seller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.marketplaceservice.testsupport.TestOutboundHttp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract test for {@link UserServiceOrganizationNameResolver} against
 * user-service's {@code GET /users/internal/organizations/names}.
 *
 * <p>Pins the wire shape of an endpoint in ANOTHER REPOSITORY
 * ({@code MpofuSlim/ticketing-system}), so a reshape there fails this build
 * rather than quietly un-naming every seller in production. Every stub is
 * transcribed from user-service's {@code InternalOrganizationController}: the
 * standard {@code ApiResult} envelope with a {@code data} list of
 * {@code {organizationId, name}}, unknown ids simply absent, and a plain 400
 * above 200 ids per call.
 *
 * <p>Also pins what a sick user-service may cost this service: every failure
 * shape backs off for the window (no call at all until it lapses, then one
 * probe), the read timeout is the resolver's own, and nothing goes on the wire
 * while a transaction is active.
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}: the resolver is built
 * exactly as its bean is, just pointed at WireMock's port. Production passes
 * the {@code @LoadBalanced} builder so {@code user-service} resolves through
 * the discovery map; a plain builder with an absolute base URL runs identical code.
 */
class UserServiceOrganizationNameResolverContractTest {

    private static final String TOKEN = "the-shared-secret";
    private static final String PATH = "/users/internal/organizations/names";
    private static final UUID A = UUID.fromString("b3f1c9d2-4a77-4e21-9c60-11ab22cd33ef");
    private static final UUID B = UUID.fromString("7c2e8a4d-1f35-4b90-8de1-2a0c5b6f9e34");
    private static final long BACKOFF_SECONDS = 30;

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        warmUp();
    }

    /**
     * One unhurried round trip before any case runs. A cold WireMock (and a cold client) can take over
     * a second to answer its first requests, longer than the resolver's own 1s read timeout — so the
     * first cases timed out, and their late requests were journaled into the NEXT case's count
     * ({@code failuresAreNotCachedButBackOff} failed this way on main). Warming the server and the
     * pooled transport here keeps every case's budget its own.
     */
    private static void warmUp() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","message":"Organization names","data":[]}""")));
        RestClient warm = RestClient.builder().baseUrl("http://localhost:" + wireMock.port())
                .requestFactory(TestOutboundHttp.POOL.requestFactory(5_000, 10_000)).build();
        for (int i = 0; i < 3; i++) {
            warm.get().uri(PATH + "?ids=" + UUID.randomUUID()).retrieve().toBodilessEntity();
        }
        wireMock.resetAll();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    /** Moved by hand, so the backoff window can lapse without sleeping. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T08:00:00Z");

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

    private TestClock clock;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void freshClockAndMeters() {
        clock = new TestClock();
        meters = new SimpleMeterRegistry();
    }

    /** ttl 0 keeps each case independent — the cache has its own test below. */
    private UserServiceOrganizationNameResolver resolver() {
        return resolver("http://localhost:" + wireMock.port(), TOKEN, 0);
    }

    private UserServiceOrganizationNameResolver resolver(String baseUrl, String token, long ttlSeconds) {
        return resolver(baseUrl, token, ttlSeconds, 1000);
    }

    /** Built exactly as the bean is, with the production defaults for the
     *  connect timeout and the backoff window. */
    private UserServiceOrganizationNameResolver resolver(String baseUrl, String token, long ttlSeconds,
                                                         int readTimeoutMs) {
        return new UserServiceOrganizationNameResolver(TestOutboundHttp.POOL,
                RestClient.builder(), baseUrl, 500, readTimeoutMs, ttlSeconds, BACKOFF_SECONDS,
                token, meters, clock);
    }

    private double outcome(String outcome) {
        Counter counter = meters.find("marketplace.merchant_names").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private void stubRudo() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[
                          {"organizationId":"%s","name":"Rudo Traders"}]}""".formatted(A))));
    }

    /**
     * The backoff contract, asserted the same way for every failure shape: the
     * failing call is the LAST one for the whole window (the next page renders
     * without asking), and once the window lapses the next page asks again and
     * a healthy answer names the seller.
     */
    private void assertBacksOffThenRecovers(UserServiceOrganizationNameResolver resolver) {
        assertThat(resolver.namesFor(List.of(A))).isEmpty();
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("failed")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(BACKOFF_SECONDS - 1));
        assertThat(resolver.namesFor(List.of(A))).isEmpty();
        // Still inside the window: not one more request reached user-service.
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("backoff")).isEqualTo(1);

        wireMock.resetAll();
        stubRudo();
        clock.advance(Duration.ofSeconds(1));
        assertThat(resolver.namesFor(List.of(A))).containsEntry(A, "Rudo Traders");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("resolved")).isEqualTo(1);
    }

    @Test
    @DisplayName("200: names come back keyed by id, and the shared token rides the request")
    void resolvesNames() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[
                          {"organizationId":"%s","name":"Rudo Traders"},
                          {"organizationId":"%s","name":"Chipo Electronics"}
                        ]}""".formatted(A, B))));

        Map<UUID, String> names = resolver().namesFor(List.of(A, B));

        assertThat(names).containsEntry(A, "Rudo Traders").containsEntry(B, "Chipo Electronics");
        // The outbound contract matters as much as the inbound one: a dropped
        // token header is a 401 nobody sees, and the ids ride as one CSV param.
        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withQueryParam("ids", equalTo(A + "," + B)));
    }

    @Test
    @DisplayName("An id user-service omits simply has no name — the rest of the page still resolves")
    void omittedIdIsNotFatal() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[
                          {"organizationId":"%s","name":"Rudo Traders"}]}""".formatted(A))));

        Map<UUID, String> names = resolver().namesFor(List.of(A, B));

        assertThat(names).containsEntry(A, "Rudo Traders").doesNotContainKey(B);
    }

    @Test
    @DisplayName("DEFENSIVE (not an observed shape): a null name is absent, not an empty string")
    void nullNameIsAbsent() {
        // user-service CANNOT currently emit this: organizations.name is NOT
        // NULL, so a known organization always carries a name and a missing
        // row means the id names nothing. Kept as client hardening against a
        // future nullable column -- and labelled, because a stub of a shape
        // nobody has observed pins our assumption rather than their contract.
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[
                          {"organizationId":"%s","name":null}]}""".formatted(A))));

        assertThat(resolver().namesFor(List.of(A))).doesNotContainKey(A);
    }

    @Test
    @DisplayName("404 — a user-service without the organization surface — is silence, not a broken catalogue")
    void notFoundIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        // This is what makes the deploy order free: marketplace can ship first.
        assertThat(resolver().namesFor(List.of(A))).isEmpty();
    }

    @Test
    @DisplayName("A connect-refused user-service is silence, not a 500 on the catalogue")
    void connectRefusedIsSilence() {
        // A port nothing is listening on — the shape of user-service being down.
        UserServiceOrganizationNameResolver dead = resolver("http://localhost:1", TOKEN, 0);

        assertThatCode(() -> assertThat(dead.namesFor(List.of(A))).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A blank internal token never reaches the network")
    void blankTokenIsNotACall() {
        assertThat(resolver("http://localhost:" + wireMock.port(), "  ", 0)
                .namesFor(List.of(A))).isEmpty();

        // The guard rail: a cell mid-provisioning must not spray unauthenticated
        // calls at user-service on every catalogue page.
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("No ids means no call at all")
    void emptyAskIsNotACall() {
        assertThat(resolver().namesFor(List.of())).isEmpty();
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("A resolved name is cached: the second page does not re-ask")
    void successesAreCached() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[
                          {"organizationId":"%s","name":"Rudo Traders"}]}""".formatted(A))));
        UserServiceOrganizationNameResolver cached = resolver("http://localhost:" + wireMock.port(), TOKEN, 300);

        assertThat(cached.namesFor(List.of(A))).containsEntry(A, "Rudo Traders");
        assertThat(cached.namesFor(List.of(A))).containsEntry(A, "Rudo Traders");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("A FAILED lookup is never cached as a name — but it does stop the next pages asking")
    void failuresAreNotCachedButBackOff() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        UserServiceOrganizationNameResolver cached = resolver("http://localhost:" + wireMock.port(), TOKEN, 300);

        // The TTL is 300s and the backoff 30s: the name appears at the first
        // page after the backoff, not after the TTL — nothing "no name" was
        // cached, only the decision not to ask for a short while.
        assertBacksOffThenRecovers(cached);
    }

    @Test
    @DisplayName("5xx backs off: every page in the window renders without asking")
    void serverErrorBacksOff() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertBacksOffThenRecovers(resolver());
    }

    @Test
    @DisplayName("401 (token drift) backs off too — asking again changes nothing for 30s")
    void unauthorizedBacksOff() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertBacksOffThenRecovers(resolver());
    }

    @Test
    @DisplayName("A body that is not JSON (an edge's HTML page on a 200) backs off")
    void unparsableBodyBacksOff() {
        // Not a shape user-service emits: what a proxy or edge in front of it
        // answers when it is unwell. Labelled, because it pins our handling,
        // not their contract.
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("<html><body>Bad gateway</body></html>")));

        assertBacksOffThenRecovers(resolver());
    }

    @Test
    @DisplayName("A 200 without the names envelope backs off — it is not an answer")
    void wrongEnvelopeBacksOff() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"unexpected":"shape"}""")));

        assertBacksOffThenRecovers(resolver());
    }

    @Test
    @DisplayName("Connect-refused backs off")
    void connectRefusedBacksOff() {
        UserServiceOrganizationNameResolver dead = resolver("http://localhost:1", TOKEN, 0);

        assertThat(dead.namesFor(List.of(A))).isEmpty();
        clock.advance(Duration.ofSeconds(5));
        assertThat(dead.namesFor(List.of(A))).isEmpty();

        assertThat(outcome("failed")).isEqualTo(1);
        assertThat(outcome("backoff")).isEqualTo(1);
    }

    @Test
    @DisplayName("The read timeout is the resolver's own, and a timeout backs off")
    void readTimeoutIsHonouredAndBacksOff() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(3000)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"code":"200 OK","message":"Organization names","data":[]}""")));
        UserServiceOrganizationNameResolver slow =
                resolver("http://localhost:" + wireMock.port(), TOKEN, 0, 300);

        long started = System.nanoTime();
        assertThat(slow.namesFor(List.of(A))).isEmpty();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        // Gave up at ~300ms, nowhere near the 3s the stub would have taken:
        // a shopper's page is not held hostage by a label.
        assertThat(tookMs).isLessThan(2000);
        wireMock.resetAll();
        stubRudo();
        clock.advance(Duration.ofSeconds(1));
        assertThat(slow.namesFor(List.of(A))).isEmpty();
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("After the window ONE page probes; a failed probe re-opens the window")
    void failedProbeReopensTheWindow() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        UserServiceOrganizationNameResolver resolver = resolver();

        resolver.namesFor(List.of(A));
        clock.advance(Duration.ofSeconds(BACKOFF_SECONDS));
        resolver.namesFor(List.of(A));            // the probe — fails again
        clock.advance(Duration.ofSeconds(BACKOFF_SECONDS - 1));
        resolver.namesFor(List.of(A));            // inside the NEW window

        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("failed")).isEqualTo(2);
        assertThat(outcome("backoff")).isEqualTo(1);
    }

    @Test
    @DisplayName("A well-formed answer naming nobody is a success, not a failure")
    void emptyAnswerDoesNotBackOff() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[]}""")));
        UserServiceOrganizationNameResolver resolver = resolver();

        assertThat(resolver.namesFor(List.of(A))).isEmpty();
        assertThat(resolver.namesFor(List.of(A))).isEmpty();

        // An organization the registry does not know is an ANSWER; backing off
        // on it would hide every other seller's name for 30s.
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("backoff")).isZero();
    }

    @Test
    @DisplayName("Inside a transaction nothing goes on the wire — only cached names are served")
    void neverCallsOutInsideATransaction() {
        stubRudo();
        UserServiceOrganizationNameResolver cached = resolver("http://localhost:" + wireMock.port(), TOKEN, 300);
        cached.namesFor(List.of(A));                         // warms the cache, outside
        wireMock.resetAll();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // A is cached and served; B is a miss and is NOT fetched — that
            // call would hold the transaction's pooled connection for its
            // whole latency.
            assertThat(cached.namesFor(List.of(A, B)))
                    .containsEntry(A, "Rudo Traders").doesNotContainKey(B);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(outcome("in_transaction")).isEqualTo(1);
    }

    @Test
    @DisplayName("More ids than user-service takes in one call are CHUNKED, never refused")
    void largeAsksAreChunkedToTheCap() {
        // user-service answers 400 above 200 ids; a payout run or a wide page
        // can legitimately name more, and splitting beats being refused.
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"Organization names","data":[]}""")));
        List<UUID> many = java.util.stream.IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID()).toList();

        resolver().namesFor(many);

        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("400 (the over-cap refusal) is silence, never an exception")
    void badRequestIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"code":"400 BAD_REQUEST","message":"At most 200 organization ids per call","data":null}""")));

        assertThat(resolver().namesFor(List.of(A))).isEmpty();
    }
}
