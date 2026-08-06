package com.innbucks.marketplaceservice.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link WhatsAppNotificationClient}: pins behaviour against
 * the response shapes the external WhatsApp gateway's
 * {@code POST /api/messages/custom-notification} endpoint actually returns, per
 * the fleet WireMock convention (mirrors booking-service / InnRewards). Pure
 * JUnit + WireMock, no Spring context.
 */
class WhatsAppNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static WhatsAppNotificationClient client;
    private static WhatsAppProperties props;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        props = new WhatsAppProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setApiKey("test-api-key");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        client = new WhatsAppNotificationClient(restClient, props);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("happy path: gateway returns 200 → client returns normally; verifies wire contract + lowercase x-api-key")
    void sendCustomNotification_happyPath_postsExpectedPayloadAndHeader() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"queued\"}")));

        client.sendCustomNotification("+263771234567",
                "Your InnBucks Marketplace order MKT-1 (USD 25.99) is confirmed. Ref MKT-1");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/messages/custom-notification"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("x-api-key", equalTo("test-api-key"))
                .withRequestBody(matchingJsonPath("$.to", equalTo("+263771234567")))
                .withRequestBody(matchingJsonPath("$.notification",
                        equalTo("Your InnBucks Marketplace order MKT-1 (USD 25.99) is confirmed. Ref MKT-1"))));
    }

    @Test
    @DisplayName("wrong api key: gateway 401 is relayed as NotificationDeliveryException with the HTTP code")
    void sendCustomNotification_wrongKey401_relayed() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid api key\"}")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263771234567", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    @DisplayName("gateway returns 5xx → NotificationDeliveryException")
    void sendCustomNotification_gatewayReturns5xx_throws() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(503).withBody("upstream down")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263771234567", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("gateway unreachable (connect refused) → NotificationDeliveryException")
    void sendCustomNotification_unreachable_throws() throws Exception {
        // Grab a free port then close it so any connect attempt is refused —
        // keeps the shared WireMock untouched for the rest of the suite.
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient dead = RestClient.builder()
                .baseUrl("http://localhost:" + closedPort)
                .requestFactory(factory)
                .build();
        WhatsAppNotificationClient unreachable = new WhatsAppNotificationClient(dead, props);

        assertThatThrownBy(() -> unreachable.sendCustomNotification("+263771234567", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank recipient is rejected client-side; no HTTP call")
    void sendCustomNotification_blankRecipient_noNetwork() {
        assertThatThrownBy(() -> client.sendCustomNotification("  ", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Recipient");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("blank message is rejected client-side; no HTTP call")
    void sendCustomNotification_blankMessage_noNetwork() {
        assertThatThrownBy(() -> client.sendCustomNotification("+263771234567", " "))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("over-1600-char message is REFUSED client-side (not truncated); gateway never sees it")
    void sendCustomNotification_overLengthMessage_refusedNoNetwork() {
        String tooLong = "x".repeat(1601);
        assertThatThrownBy(() -> client.sendCustomNotification("+263771234567", tooLong))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("1600");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("exactly 1600 chars is legal and hits the wire")
    void sendCustomNotification_exactCap_sent() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(200)));

        client.sendCustomNotification("+263771234567", "x".repeat(1600));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }
}
