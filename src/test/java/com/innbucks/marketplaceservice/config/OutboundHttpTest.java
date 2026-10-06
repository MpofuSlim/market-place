package com.innbucks.marketplaceservice.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transport every outbound call in this service rides on. Pure JUnit +
 * WireMock: each case drives a real request through the pooled client and
 * checks what reached the wire, because the properties that matter here (no
 * retry, HTTP/1.1, no cookie carry-over) are invisible in configuration and
 * only show up as a second request, a different protocol, or a stray header.
 */
class OutboundHttpTest {

    private static WireMockServer wireMock;
    private OutboundHttp http;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void freshPool() {
        http = OutboundHttp.withDefaults(); // a fresh pool so its statistics are this test's alone
    }

    @AfterEach
    void reset() throws Exception {
        wireMock.resetAll();
        http.close();
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .requestFactory(http.requestFactory(500, 2000))
                .build();
    }

    @Test
    @DisplayName("a 503 on a GET is answered once: no automatic retry")
    void serviceUnavailable_onGet_isNotRetried() {
        wireMock.stubFor(get(urlEqualTo("/r")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1")));

        assertThatThrownBy(() -> client().get().uri("/r").retrieve().toBodilessEntity())
                .isInstanceOf(RestClientResponseException.class);

        assertSentExactlyOnce(getRequestedFor(urlEqualTo("/r")));
    }

    @Test
    @DisplayName("a 503 on a POST is answered once: a send is never re-delivered behind the caller's back")
    void serviceUnavailable_onPost_isNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/send")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client().post().uri("/send").body("{}").retrieve().toBodilessEntity())
                .isInstanceOf(RestClientResponseException.class);

        assertSentExactlyOnce(postRequestedFor(urlEqualTo("/send")));
    }

    @Test
    @DisplayName("an I/O failure on an idempotent GET is not retried either")
    void emptyResponse_onGet_isNotRetried() {
        wireMock.stubFor(get(urlEqualTo("/drop")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThatThrownBy(() -> client().get().uri("/drop").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);

        assertSentExactlyOnce(getRequestedFor(urlEqualTo("/drop")));
    }

    @Test
    @DisplayName("requests go out as HTTP/1.1")
    void speaksHttp11() {
        wireMock.stubFor(get(urlEqualTo("/v")).willReturn(ok("x")));

        client().get().uri("/v").retrieve().toBodilessEntity();

        List<LoggedRequest> seen = wireMock.findAll(getRequestedFor(urlEqualTo("/v")));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getProtocol()).isEqualTo("HTTP/1.1");
    }

    @Test
    @DisplayName("sequential calls to one host reuse one pooled connection")
    void connectionsAreReused() {
        wireMock.stubFor(get(urlEqualTo("/p")).willReturn(ok("x")));
        RestClient c = client();

        for (int i = 0; i < 5; i++) {
            c.get().uri("/p").retrieve().toBodilessEntity();
        }

        var stats = http.connectionManager().getTotalStats();
        assertThat(stats.getLeased()).isZero();
        assertThat(stats.getAvailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("a POST that is redirected is NOT followed (re-sent elsewhere); a GET is")
    void redirects_followedForGetOnly() {
        wireMock.stubFor(post(urlEqualTo("/old")).willReturn(aResponse().withStatus(302).withHeader("Location", "/new")));
        wireMock.stubFor(get(urlEqualTo("/old")).willReturn(aResponse().withStatus(302).withHeader("Location", "/new")));
        wireMock.stubFor(get(urlEqualTo("/new")).willReturn(ok("moved")));
        wireMock.stubFor(post(urlEqualTo("/new")).willReturn(ok("moved")));

        ResponseEntity<Void> posted = client().post().uri("/old").body("{}").retrieve().toBodilessEntity();
        assertThat(posted.getStatusCode().value()).isEqualTo(302);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/new")));
        wireMock.verify(0, getRequestedFor(urlEqualTo("/new")));

        String got = client().get().uri("/old").retrieve().body(String.class);
        assertThat(got).isEqualTo("moved");
    }

    @Test
    @DisplayName("a cookie one upstream sets is never sent back: the shared client keeps no cookie store")
    void noCookieStore() {
        wireMock.stubFor(get(urlEqualTo("/login")).willReturn(ok("x").withHeader("Set-Cookie", "SESSION=abc; Path=/")));
        wireMock.stubFor(get(urlEqualTo("/next")).willReturn(ok("x")));

        client().get().uri("/login").retrieve().toBodilessEntity();
        client().get().uri("/next").retrieve().toBodilessEntity();

        wireMock.verify(getRequestedFor(urlEqualTo("/next")).withHeader("Cookie", absent()));
    }

    @Test
    @DisplayName("no Accept-Encoding is added: the request headers are the caller's")
    void noTransparentCompression() {
        wireMock.stubFor(get(urlEqualTo("/h")).willReturn(ok("x")));

        client().get().uri("/h").retrieve().toBodilessEntity();

        wireMock.verify(getRequestedFor(urlEqualTo("/h")).withHeader("Accept-Encoding", absent()));
    }

    @Test
    @DisplayName("a client's own timeouts ride on every request; the pool's connection-request timeout too")
    void perClientTimeouts() {
        OutboundHttp.PooledRequestFactory f = http.requestFactory(Duration.ofMillis(750), Duration.ofMillis(4200));

        RequestConfig rc = f.effectiveRequestConfig();
        assertThat(connectTimeoutOf(rc)).isEqualTo(750);
        assertThat(rc.getResponseTimeout().toMilliseconds()).isEqualTo(4200);
        assertThat(rc.getConnectionRequestTimeout().toMilliseconds())
                .as("the pool wait is capped at the client's own, shorter, connect timeout").isEqualTo(750);
        assertThat(f.getHttpClient()).isSameAs(http.httpClient());

        RequestConfig slow = http.requestFactory(Duration.ofSeconds(5), Duration.ofSeconds(9)).effectiveRequestConfig();
        assertThat(slow.getConnectionRequestTimeout().toMilliseconds())
                .as("a longer connect timeout keeps the shared pool wait").isEqualTo(2000);
    }

    @Test
    @DisplayName("a zero or missing timeout falls back to the shared default, never to 'wait forever'")
    void nonPositiveTimeout_fallsBackToDefault() {
        OutboundHttp.PooledRequestFactory f = http.requestFactory(Duration.ZERO, null);

        assertThat(f.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(f.responseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("pool limits come from the properties (small-cell defaults: 50 total, 20 per route)")
    void poolLimits() {
        assertThat(http.connectionManager().getMaxTotal()).isEqualTo(50);
        assertThat(http.connectionManager().getDefaultMaxPerRoute()).isEqualTo(20);

        OutboundHttpProperties p = new OutboundHttpProperties();
        p.setMaxTotal(8);
        p.setMaxPerRoute(3);
        try (OutboundHttp sized = new OutboundHttp(p)) {
            assertThat(sized.connectionManager().getMaxTotal()).isEqualTo(8);
            assertThat(sized.connectionManager().getDefaultMaxPerRoute()).isEqualTo(3);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("a per-route limit above the total, or a non-positive duration, fails the boot")
    void invalidSizing_isRefused() {
        OutboundHttpProperties p = new OutboundHttpProperties();
        p.setMaxTotal(10);
        p.setMaxPerRoute(11);
        assertThatThrownBy(() -> new OutboundHttp(p)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-per-route");

        OutboundHttpProperties q = new OutboundHttpProperties();
        q.setConnectionRequestTimeout(Duration.ZERO);
        assertThatThrownBy(() -> new OutboundHttp(q)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection-request-timeout");
    }

    @Test
    @DisplayName("destroying one factory never closes the shared client")
    void factoryDestroy_leavesSharedClientOpen() {
        wireMock.stubFor(get(urlEqualTo("/alive")).willReturn(ok("x")));
        OutboundHttp.PooledRequestFactory f = http.requestFactory();

        f.destroy();

        client().get().uri("/alive").retrieve().toBodilessEntity();
        wireMock.verify(1, getRequestedFor(urlEqualTo("/alive")));
    }

    @Test
    @DisplayName("the pool is published on Micrometer")
    void poolMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboundHttpConfig().outboundHttpPoolMetrics(http).bindTo(registry);

        assertThat(registry.get("httpcomponents.httpclient.pool.total.max")
                .tag("httpclient", "outbound").gauge().value()).isEqualTo(50.0);
        assertThat(registry.get("httpcomponents.httpclient.pool.total.pending")
                .tag("httpclient", "outbound").gauge().value()).isZero();
    }

    /**
     * WireMock journals a request on its own thread, and for a fault only after it has broken the
     * connection — so on a loaded machine the client can see the failure before the journal does.
     * Wait for the first request, then give a retry (were one coming) time to land, then count.
     */
    private static void assertSentExactlyOnce(RequestPatternBuilder request) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            while (wireMock.findAll(request).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        wireMock.verify(1, request);
    }

    @SuppressWarnings("deprecation")
    static long connectTimeoutOf(RequestConfig rc) {
        return rc.getConnectTimeout().toMilliseconds();
    }
}
