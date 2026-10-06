package com.innbucks.marketplaceservice.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the service's one pooled outbound HTTP client ({@link OutboundHttp})
 * and publishes its pool on Micrometer. The context closes the client — and with
 * it the pool and its idle-connection evictor — on shutdown.
 *
 * <p>Pool metrics ({@code httpcomponents.httpclient.pool.*}, tagged
 * {@code httpclient=outbound}): {@code total.max}, {@code total.connections}
 * by {@code state} (leased / available), {@code total.pending} and
 * {@code route.max.default}. A sustained non-zero {@code total.pending} means
 * callers are queueing for a connection: an upstream is slow, or the pool is
 * too small for the load.
 */
@Configuration
@EnableConfigurationProperties(OutboundHttpProperties.class)
public class OutboundHttpConfig {

    @Bean(destroyMethod = "close")
    public OutboundHttp outboundHttp(OutboundHttpProperties properties) {
        return new OutboundHttp(properties);
    }

    @Bean
    public MeterBinder outboundHttpPoolMetrics(OutboundHttp outboundHttp) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(
                outboundHttp.connectionManager(), "outbound");
    }
}
