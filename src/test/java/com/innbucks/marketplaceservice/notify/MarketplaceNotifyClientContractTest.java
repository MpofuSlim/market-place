package com.innbucks.marketplaceservice.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the marketplace's copy of the fleet notification-API
 * client ({@link EmailNotificationClient} + the delegating
 * {@link SmsNotificationClient}) against the InnBucks public notification API:
 * {@code POST /auth/third-party} for the bearer (cached until the JWT exp,
 * ONE forced refresh-and-replay on a 401), then
 * {@code POST /api/notification/sms} with body
 * {@code {message, reference, destinationMsisdn}} /
 * {@code POST /api/notification/email} with
 * {@code {subject, message, reference, destinationEmail}}, both with
 * {@code X-Api-Key} + Bearer. Pins the wire shape so a regression (renamed
 * field, dropped header, missing auth) fails the build — per the fleet
 * WireMock mandate; mirrors user-service's SmsNotificationClientContractTest.
 * Pure JUnit + WireMock, no Spring context.
 */
class MarketplaceNotifyClientContractTest {

    private static final String LOGIN = "/auth/third-party";
    private static final String SMS = "/api/notification/sms";
    private static final String EMAIL = "/api/notification/email";
    private static final String API_KEY = "test-api-key";

    private static WireMockServer wireMock;

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

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static EmailNotificationClient apiClient(int port) {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl("http://localhost:" + port);
        props.setApiKey(API_KEY);
        props.setUsername("test-user");
        props.setPassword("test-pass");
        // Notification-API-only constructor (no SMTP sender): the contract
        // under test is the API wire format.
        return new EmailNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + port).build(),
                props, new ObjectMapper());
    }

    private static SmsNotificationClient smsClient(int port) {
        return new SmsNotificationClient(apiClient(port));
    }

    private static void stubHappyGateway() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));
    }

    // ------------------------------------------------------------------
    // SMS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SMS happy path: logs in then posts {message,reference,destinationMsisdn} with X-Api-Key + Bearer")
    void sendSms_postsDocumentedShape() {
        stubHappyGateway();

        smsClient(wireMock.port()).sendSms("+263771234567",
                "Your InnBucks Marketplace order MKT-1 (USD 25.99) is confirmed. Ref MKT-1", "MKT-1");

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo("Your InnBucks Marketplace order MKT-1 (USD 25.99) is confirmed. Ref MKT-1")))
                .withRequestBody(matchingJsonPath("$.destinationMsisdn", equalTo("+263771234567")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("MKT-1"))));
    }

    @Test
    @DisplayName("bearer caching: two sends, ONE login")
    void tokenIsCachedAcrossSends() {
        stubHappyGateway();
        SmsNotificationClient client = smsClient(wireMock.port());

        client.sendSms("+263771234567", "first", "R-1");
        client.sendSms("+263771234567", "second", "R-2");

        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("SMS body is GSM-transliterated on the wire (em-dash/colon would draw 400 Invalid message)")
    void sendSms_sanitizesTheMessage() {
        stubHappyGateway();

        smsClient(wireMock.port()).sendSms("+263771234567",
                "Ready — collect now: it’s here!", "R-1");

        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo("Ready - collect now it's here."))));
    }

    @Test
    @DisplayName("blank SMS reference: auto-fills MKT-SMS-<uuid>")
    void blankSmsReference_autoFilled() {
        stubHappyGateway();

        smsClient(wireMock.port()).sendSms("+263771234567", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withRequestBody(matchingJsonPath("$.reference", matching("MKT-SMS-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("blank SMS recipient/message: guarded before any network call")
    void blankSmsInputs_neverHitTheWire() {
        SmsNotificationClient client = smsClient(wireMock.port());
        assertThatThrownBy(() -> client.sendSms(" ", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendSms("+263771234567", " ", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("unconfigured channel (blank creds): refused client-side, zero wire traffic")
    void unconfigured_neverHitsTheWire() {
        InnbucksNotifyProperties blank = new InnbucksNotifyProperties();
        blank.setBaseUrl("http://localhost:" + wireMock.port());
        EmailNotificationClient client = new EmailNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + wireMock.port()).build(),
                blank, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.sendSms("+263771234567", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not configured");
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("401 on SMS: refresh token and replay ONCE, then succeed")
    void unauthorized_refreshesAndReplays() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).inScenario("auth")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMock.stubFor(post(urlEqualTo(SMS)).inScenario("auth")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> smsClient(wireMock.port()).sendSms("+263771234567", "m", "r"))
                .doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(SMS)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("persistent 401: refresh once then give up with NotificationDeliveryException")
    void unauthorizedTwice_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> smsClient(wireMock.port()).sendSms("+263771234567", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("login rejected (401): NotificationDeliveryException, SMS never attempted")
    void loginRejected_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> smsClient(wireMock.port()).sendSms("+263771234567", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("login failed");
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("500 on SMS (non-401): NotificationDeliveryException")
    void serverError_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> smsClient(wireMock.port()).sendSms("+263771234567", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("connect refused: NotificationDeliveryException (separate dead-port client)")
    void connectRefused_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> smsClient(closedPort).sendSms("+263771234567", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    // ------------------------------------------------------------------
    // Email
    // ------------------------------------------------------------------

    @Test
    @DisplayName("email happy path: posts {subject,message,reference,destinationEmail}; body carried + branded, subject GSM-safe")
    void sendEmail_postsDocumentedShape() {
        stubHappyGateway();

        apiClient(wireMock.port()).sendEmail("buyer@example.com",
                "Your InnBucks Marketplace order — MKT-1", "Order MKT-1 is confirmed.", "MKT-1");

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                // Subject transliterated (the endpoint charset-validates it —
                // an em-dash draws 400 "Invalid subject").
                .withRequestBody(matchingJsonPath("$.subject",
                        equalTo("Your InnBucks Marketplace order - MKT-1")))
                // The caller's body is carried inside the branded HTML shell
                // (html-enabled default) with the standard footer.
                .withRequestBody(matchingJsonPath("$.message", containing("<table")))
                .withRequestBody(matchingJsonPath("$.message", containing("Order MKT-1 is confirmed.")))
                .withRequestBody(matchingJsonPath("$.message", containing("Deposit Protection Scheme")))
                .withRequestBody(matchingJsonPath("$.destinationEmail", equalTo("buyer@example.com")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("MKT-1"))));
    }

    @Test
    @DisplayName("blank email reference: auto-fills MKT-EMAIL-<uuid>")
    void blankEmailReference_autoFilled() {
        stubHappyGateway();

        apiClient(wireMock.port()).sendEmail("buyer@example.com", "s", "m", null);

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.reference", matching("MKT-EMAIL-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("blank email recipient/subject/message: guarded before any network call")
    void blankEmailInputs_neverHitTheWire() {
        EmailNotificationClient client = apiClient(wireMock.port());
        assertThatThrownBy(() -> client.sendEmail(" ", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("buyer@example.com", " ", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("buyer@example.com", "s", " ", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(EMAIL)));
    }
}
