package com.innbucks.marketplaceservice.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract test for {@link UserServiceMerchantAdminResolver} against
 * user-service's {@code GET /users/internal/organizations/{id}/admins} — the
 * OWNERs and ADMINs, with an active account, of the organization that sells
 * (a seller's {@code merchantId} IS that organization's id).
 *
 * <p>Pins the wire shape of an endpoint in ANOTHER REPOSITORY
 * ({@code MpofuSlim/ticketing-system}), so a reshape there fails this build
 * rather than quietly resolving nobody in production. Every stub transcribes
 * what that controller actually returns — the fleet {@code ApiResult} envelope
 * with {@code data} — and the refusal cases are the ways the chain
 * legitimately ends in silence.
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}: the resolver is built
 * exactly as its bean is, just pointed at WireMock's port. Production passes
 * the {@code @LoadBalanced} builder so {@code user-service} resolves through
 * Eureka; a plain builder with an absolute base URL runs the identical code
 * without a registry.
 */
class UserServiceMerchantAdminResolverContractTest {

    private static final String TOKEN = "the-shared-secret";
    private static final UUID MERCHANT = UUID.fromString("b3f1c9d2-4a77-4e21-9c60-11ab22cd33ef");
    private static final String PATH = "/users/internal/organizations/" + MERCHANT + "/admins";
    private static final UUID ADMIN = UUID.fromString("7c2e8a4d-1f35-4b90-8de1-2a0c5b6f9e34");

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

    private UserServiceMerchantAdminResolver resolver() {
        return resolver("http://localhost:" + wireMock.port(), TOKEN);
    }

    private UserServiceMerchantAdminResolver resolver(String baseUrl, String token) {
        return new UserServiceMerchantAdminResolver(RestClient.builder(), baseUrl, 2000, 5000, token);
    }

    @Test
    @DisplayName("200: the admin's userUuid comes back, and the shared token rides the request")
    void resolvesTheAdmin() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","message":"Organization admins",
                 "data":[{"userUuid":"%s","email":"chipo@merchant.test"}]}""".formatted(ADMIN))));

        assertThat(resolver().adminUserUuids(MERCHANT)).containsExactly(ADMIN);

        // The outbound half matters as much: without the shared secret
        // user-service answers 401 and every merchant silently resolves to
        // nobody — which looks identical to "this merchant has no admin".
        wireMock.verify(getRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("Several admins all come back, in the order user-service listed them")
    void resolvesEveryAdmin() {
        UUID second = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","data":[{"userUuid":"%s"},{"userUuid":"%s"}]}"""
                .formatted(ADMIN, second))));

        assertThat(resolver().adminUserUuids(MERCHANT)).containsExactly(ADMIN, second);
    }

    @Test
    @DisplayName("200 with an empty data array: nobody, not an error")
    void emptyDataIsNobody() {
        // user-service's answer for an unknown organization AND for one whose
        // OWNERs/ADMINs are all inactive — it collapses them deliberately, so
        // the S2S surface is no existence oracle.
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","message":"Organization admins","data":[]}""")));

        assertThat(resolver().adminUserUuids(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("A missing data key is nobody, never a crash")
    void absentDataIsNobody() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","message":"Organization admins"}""")));

        assertThat(resolver().adminUserUuids(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("One unparseable id is skipped — it must not cost the others their message")
    void anUnparseableIdIsSkipped() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","data":[{"userUuid":"not-a-uuid"},{"userUuid":"%s"},{"userUuid":""}]}"""
                .formatted(ADMIN))));

        assertThat(resolver().adminUserUuids(MERCHANT)).containsExactly(ADMIN);
    }

    @Test
    @DisplayName("404: nobody — a user-service without the organization surface reads as no admins")
    void notFoundIsNobody() {
        // This is the deploy-order case: marketplace can ship before
        // user-service does, and must degrade to today's behaviour rather than
        // erroring inside a payment confirm's after-commit listener.
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(resolver().adminUserUuids(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("401 (token drift): nobody, and nothing thrown")
    void unauthorizedIsNobody() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(resolver().adminUserUuids(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("500: nobody, and nothing thrown")
    void serverErrorIsNobody() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(resolver().adminUserUuids(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("A body that is not the envelope at all: nobody, and NOTHING escapes")
    void garbageBodyNeverEscapes() {
        // The caller is inside a never-throws after-commit listener; an
        // exception here would surface as a failed payment confirm.
        wireMock.stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>Request Rejected</html>")));

        assertThatCode(() -> assertThat(resolver().adminUserUuids(MERCHANT)).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Connect refused: nobody, and nothing thrown")
    void connectRefusedIsNobody() {
        // A separate resolver at a known-closed port; never stop/restart the
        // shared WireMock, whose second start gets a different dynamic port.
        UserServiceMerchantAdminResolver offline = resolver("http://localhost:1", TOKEN);

        assertThat(offline.adminUserUuids(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("Guard rails: a null merchant and an unconfigured token never hit the wire")
    void guardRailsNeverCallOut() {
        assertThat(resolver().adminUserUuids(null)).isEmpty();
        assertThat(resolver("http://localhost:" + wireMock.port(), "   ")
                .adminUserUuids(MERCHANT)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/users/internal/.*")));
    }

    @Test
    @DisplayName("It addresses the merchant it was asked about, not the last one")
    void addressesTheMerchantItWasAsked() {
        UUID other = UUID.randomUUID();
        UUID othersAdmin = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/users/internal/organizations/" + other + "/admins"))
                .willReturn(okJson("""
                        {"code":"200 OK","data":[{"userUuid":"%s"}]}""".formatted(othersAdmin))));
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","data":[{"userUuid":"%s"}]}""".formatted(ADMIN))));

        assertThat(resolver().adminUserUuids(MERCHANT)).containsExactly(ADMIN);
        assertThat(resolver().adminUserUuids(other)).containsExactly(othersAdmin);
    }
}
