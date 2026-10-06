package com.innbucks.marketplaceservice.config;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.InnbucksNotifyProperties;
import com.innbucks.marketplaceservice.notify.NotificationClientConfig;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
import com.innbucks.marketplaceservice.notify.UserServiceMerchantAdminResolver;
import com.innbucks.marketplaceservice.notify.WhatsAppProperties;
import com.innbucks.marketplaceservice.seller.UserServiceOrganizationNameResolver;
import com.innbucks.marketplaceservice.testsupport.TestOutboundHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound {@code RestClient} in the service draws on the ONE pool and
 * keeps its own timeouts. A client built on Spring's default factory (or a
 * fresh {@code SimpleClientHttpRequestFactory}) fails here — that is the
 * regression this pins: an unpooled client opens a TCP (and TLS) connection
 * per call, and a default factory can silently change transport.
 */
class OutboundHttpWiringTest {

    private static final OutboundHttp POOL = TestOutboundHttp.POOL;

    @Test
    @DisplayName("UserNotifyGateway: pooled over the load-balanced builder, with its own timeouts")
    void userNotifyGateway() {
        var c = new UserNotifyGateway(POOL, RestClient.builder(), "http://user-service", 2100, 5100, "t",
                new MarketplaceMetrics(new SimpleMeterRegistry()));
        assertPooled(field(c), 2100, 5100);
    }

    @Test
    @DisplayName("UserServiceMerchantAdminResolver: pooled over the load-balanced builder, with its own timeouts")
    void merchantAdminResolver() {
        var c = new UserServiceMerchantAdminResolver(POOL, RestClient.builder(), "http://user-service", 2200, 5200, "t");
        assertPooled(field(c), 2200, 5200);
    }

    @Test
    @DisplayName("UserServiceOrganizationNameResolver: pooled, keeping its tight catalogue-path budget")
    void organizationNameResolver() {
        var c = new UserServiceOrganizationNameResolver(POOL, RestClient.builder(), "http://user-service",
                500, 1000, 300, 30, "t", new SimpleMeterRegistry());
        assertPooled(field(c), 500, 1000);
    }

    @Test
    @DisplayName("notification API + WhatsApp RestClient beans: pooled, with their properties' timeouts")
    void notificationClients() {
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        notify.setBaseUrl("http://notify");
        notify.setConnectTimeoutMs(3400);
        notify.setReadTimeoutMs(20400);
        WhatsAppProperties wa = new WhatsAppProperties();
        wa.setBaseUrl("http://wa");
        wa.setApiKey("k");
        wa.setConnectTimeoutMs(2500);
        wa.setReadTimeoutMs(10500);
        NotificationClientConfig cfg = new NotificationClientConfig(notify, wa);

        assertPooled(cfg.innbucksNotifyRestClient(notify, POOL), 3400, 20400);
        assertPooled(cfg.whatsAppRestClient(wa, POOL), 2500, 10500);
    }

    @Test
    @DisplayName("both RestClient.Builder beans start on the pool with the shared default timeouts")
    void builders() {
        LoadBalancedRestClientConfig cfg = new LoadBalancedRestClientConfig();
        assertPooled(cfg.restClientBuilder(POOL).build(), 2000, 10000);
        assertPooled(cfg.loadBalancedRestClientBuilder(POOL).build(), 2000, 10000);
    }

    @Test
    @DisplayName("the property defaults: shipped timeouts the clients above keep are unchanged")
    void shippedDefaultsAreKept() {
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        assertThat(notify.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(notify.getReadTimeoutMs()).isEqualTo(20000);
        WhatsAppProperties wa = new WhatsAppProperties();
        assertThat(wa.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(wa.getReadTimeoutMs()).isEqualTo(10000);
    }

    private static RestClient field(Object client) {
        return (RestClient) ReflectionTestUtils.getField(client, "restClient");
    }

    private static void assertPooled(RestClient rc, long connectMs, long readMs) {
        ClientHttpRequestFactory f = (ClientHttpRequestFactory) ReflectionTestUtils.getField(rc, "clientRequestFactory");
        assertThat(f).isInstanceOf(OutboundHttp.PooledRequestFactory.class);
        OutboundHttp.PooledRequestFactory pooled = (OutboundHttp.PooledRequestFactory) f;
        assertThat(pooled.getHttpClient()).as("the shared pool's client").isSameAs(POOL.httpClient());
        assertThat(pooled.connectTimeout()).isEqualTo(Duration.ofMillis(connectMs));
        assertThat(pooled.responseTimeout()).isEqualTo(Duration.ofMillis(readMs));
        var rcfg = pooled.effectiveRequestConfig();
        assertThat(OutboundHttpTest.connectTimeoutOf(rcfg)).isEqualTo(connectMs);
        assertThat(rcfg.getResponseTimeout().toMilliseconds()).isEqualTo(readMs);
        assertThat(rcfg.getConnectionRequestTimeout().toMilliseconds()).isEqualTo(Math.min(2000, connectMs));
    }
}
