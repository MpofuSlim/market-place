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
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}: the resolver is built
 * exactly as its bean is, just pointed at WireMock's port. Production passes
 * the {@code @LoadBalanced} builder so {@code user-service} resolves through
 * Eureka; a plain builder with an absolute base URL runs identical code.
 */
class UserServiceOrganizationNameResolverContractTest {

    private static final String TOKEN = "the-shared-secret";
    private static final String PATH = "/users/internal/organizations/names";
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
    private UserServiceOrganizationNameResolver resolver() {
        return resolver("http://localhost:" + wireMock.port(), TOKEN, 0);
    }

    private UserServiceOrganizationNameResolver resolver(String baseUrl, String token, long ttlSeconds) {
        return new UserServiceOrganizationNameResolver(
                RestClient.builder(), baseUrl, 2000, 5000, ttlSeconds, token);
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
    @DisplayName("401 (token drift) is silence, never an exception")
    void unauthorizedIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatCode(() -> assertThat(resolver().namesFor(List.of(A))).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("404 — a user-service without the organization surface — is silence, not a broken catalogue")
    void notFoundIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        // This is what makes the deploy order free: marketplace can ship first.
        assertThat(resolver().namesFor(List.of(A))).isEmpty();
    }

    @Test
    @DisplayName("500 is silence — a user-service outage must never fail a shopper's browse")
    void serverErrorIsSilence() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

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
    @DisplayName("A FAILED lookup is never cached — a blip must not pin a seller nameless")
    void failuresAreNotCached() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        UserServiceOrganizationNameResolver cached = resolver("http://localhost:" + wireMock.port(), TOKEN, 300);

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
