package com.innbucks.marketplaceservice.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.innbucks.marketplaceservice.testsupport.TestOutboundHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * The notification client's token handling under concurrency, against
 * WireMock: concurrent senders share one login, and concurrent 401s on the
 * same token share one re-login. The wire contract itself is pinned by
 * {@link MarketplaceNotifyClientContractTest}; this class only counts logins.
 */
class EmailNotificationClientTokenRefreshTest {

    private static final String LOGIN = "/auth/third-party";
    private static final String SMS = "/api/notification/sms";
    private static final int SENDERS = 12;

    private static WireMockServer wireMock;
    private final ExecutorService pool = Executors.newFixedThreadPool(SENDERS);

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
        pool.shutdownNow();
        wireMock.resetAll();
    }

    private static EmailNotificationClient client() {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl(wireMock.baseUrl());
        props.setApiKey("test-api-key");
        props.setUsername("test-user");
        props.setPassword("test-pass");
        return new EmailNotificationClient(
                new NotificationClientConfig(props, new WhatsAppProperties()).innbucksNotifyRestClient(props, TestOutboundHttp.POOL),
                props, new ObjectMapper());
    }

    private void sendConcurrently(EmailNotificationClient client) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < SENDERS; i++) {
            String ref = "R-" + i;
            sends.add(pool.submit(() -> {
                go.await();
                client.sendSms("+263771234567", "hello", ref);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : sends) {
            f.get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("concurrent cold senders share ONE slow login")
    void concurrentColdSendersShareOneLogin() throws Exception {
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(okJson("{\"accessToken\":\"tok-1\"}").withFixedDelay(500)));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));

        sendConcurrently(client());

        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(SENDERS, postRequestedFor(urlEqualTo(SMS))
                .withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    @Test
    @DisplayName("concurrent 401s on the same token cause ONE re-login, and every send is replayed")
    void concurrentUnauthorizedShareOneReLogin() throws Exception {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"accessToken\":\"tok-1\"}").withFixedDelay(300))
                .willSetStateTo("second"));
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"accessToken\":\"tok-2\"}").withFixedDelay(300)));
        wireMock.stubFor(post(urlEqualTo(SMS)).withHeader("Authorization", equalTo("Bearer tok-1"))
                .willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(post(urlEqualTo(SMS)).withHeader("Authorization", equalTo("Bearer tok-2"))
                .willReturn(aResponse().withStatus(200)));

        sendConcurrently(client());

        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(SENDERS, postRequestedFor(urlEqualTo(SMS))
                .withHeader("Authorization", equalTo("Bearer tok-2")));
    }
}
