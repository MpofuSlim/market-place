package com.innbucks.marketplaceservice.seller;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract test for {@link LoyaltyMerchantNameResolver} against loyalty's
 * {@code GET /loyalty/internal/merchants/names}.
 *
 * <p>Pins the wire shape of an endpoint in ANOTHER REPOSITORY
 * ({@code MpofuSlim/InnRewards}), so a reshape there fails this build rather
 * than quietly un-naming every seller in production. Loyalty's internal
 * surface serves PLAIN maps — not the {@code ApiResult} envelope the rest of
 * the fleet uses — and every stub here transcribes that.
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}: the resolver is built
 * exactly as its bean is, just pointed at WireMock's port. Production passes
 * the {@code @LoadBalanced} builder so {@code loyalty-service} resolves through
 * Eureka; a plain builder with an absolute base URL runs identical code.
 */
class LoyaltyMerchantNameResolverContractTest {

    private static final String TOKEN = "the-shared-secret";
    private static final String PATH = "/loyalty/internal/merchants/names";
    private static final UUID A = UUID.fromString("b3f1c9d2-4a77-4e21-9c60-11ab22cd33ef");
    private static final UUID B = UUID.fromString("7c2e8a4d-1f35-4b90-8de1-2a0c5b6f9e34");

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    /** ttl 0 keeps each case independent — the cache has its own test below. */
    private LoyaltyMerchantNameResolver resolver() {
        return resolver("http://localhost:" + wireMock.port(), TOKEN, 0);
    }

    private LoyaltyMerchantNameResolver resolver(String baseUrl, String token, long ttlSeconds) {
        return new LoyaltyMerchantNameResolver(
                RestClient.builder(), baseUrl, 2000, 5000, ttlSeconds, token);
    }

    @Test
    @DisplayName("200: names come back keyed by id, and the shared token rides the request")
    void resolvesNames() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"merchants":[
                          {"merchantId":"%s","name":"Rudo Traders"},
                          {"merchantId":"%s","name":"Chipo Electronics"}
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
    @DisplayName("An id loyalty omits simply has no name — the rest of the page still resolves")
    void omittedIdIsNotFatal() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"merchants":[{"merchantId":"%s","name":"Rudo Traders"}]}""".formatted(A))));

        Map<UUID, String> names = resolver().namesFor(List.of(A, B));

        assertThat(names).containsEntry(A, "Rudo Traders").doesNotContainKey(B);
    }

    @Test
    @DisplayName("A merchant with a null name is absent, not an empty string")
    void nullNameIsAbsent() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("""
                        {"merchants":[{"merchantId":"%s","name":null}]}""".formatted(A))));

        assertThat(resolver().namesFor(List.of(A))).doesNotContainKey(A);
    }

    @Test
    @DisplayName("401 (token drift) is silence, never an exception")
    void unauthorizedIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatCode(() -> assertThat(resolver().namesFor(List.of(A))).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("404 — a loyalty too old to serve this yet — is silence, not a broken catalogue")
    void notFoundIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        // This is what makes the deploy order free: marketplace can ship first.
        assertThat(resolver().namesFor(List.of(A))).isEmpty();
    }

    @Test
    @DisplayName("500 is silence — a loyalty outage must never fail a shopper's browse")
    void serverErrorIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(resolver().namesFor(List.of(A))).isEmpty();
    }

    @Test
    @DisplayName("A connect-refused loyalty is silence, not a 500 on the catalogue")
    void connectRefusedIsSilence() {
        // A port nothing is listening on — the shape of loyalty being down.
        LoyaltyMerchantNameResolver dead = resolver("http://localhost:1", TOKEN, 0);

        assertThatCode(() -> assertThat(dead.namesFor(List.of(A))).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A blank internal token never reaches the network")
    void blankTokenIsNotACall() {
        assertThat(resolver("http://localhost:" + wireMock.port(), "  ", 0)
                .namesFor(List.of(A))).isEmpty();

        // The guard rail: a cell mid-provisioning must not spray unauthenticated
        // calls at loyalty on every catalogue page.
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
                        {"merchants":[{"merchantId":"%s","name":"Rudo Traders"}]}""".formatted(A))));
        LoyaltyMerchantNameResolver cached = resolver("http://localhost:" + wireMock.port(), TOKEN, 300);

        assertThat(cached.namesFor(List.of(A))).containsEntry(A, "Rudo Traders");
        assertThat(cached.namesFor(List.of(A))).containsEntry(A, "Rudo Traders");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("A FAILED lookup is never cached — a blip must not pin a seller nameless")
    void failuresAreNotCached() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        LoyaltyMerchantNameResolver cached = resolver("http://localhost:" + wireMock.port(), TOKEN, 300);

        assertThat(cached.namesFor(List.of(A))).isEmpty();
        assertThat(cached.namesFor(List.of(A))).isEmpty();

        // Both attempts hit the wire: the next page retries rather than serving
        // a cached "no name" for the whole TTL.
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("A body that is not the agreed shape is silence, not a parse failure")
    void unexpectedBodyIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"unexpected":"shape"}""")));

        assertThat(resolver().namesFor(List.of(A))).isEmpty();
    }
}
