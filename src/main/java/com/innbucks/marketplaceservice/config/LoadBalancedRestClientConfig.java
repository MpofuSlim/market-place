package com.innbucks.marketplaceservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.Builder} that resolves {@code http://<service-name>} URLs
 * via Spring Cloud LoadBalancer, through the static discovery map in
 * application.yaml (the sibling's k8s Service) (fleet copy —
 * InnRewards). {@code UserNotifyGateway} clones this builder so its
 * {@code http://user-service} calls are discovery-routed instead of hitting a
 * hardcoded host:port.
 *
 * <p>Both builders start on the service's pooled request factory
 * ({@link OutboundHttp}) with its default timeouts, so nothing built from them
 * can fall back to Spring's classpath-detected default factory. A client that
 * needs its own timeouts sets its own pooled factory on a clone.
 */
@Configuration
public class LoadBalancedRestClientConfig {

    /**
     * The plain (non-load-balanced) builder, kept @Primary so anything that
     * autowires a RestClient.Builder by type gets this one: a client that
     * calls a fixed external URL (the notification gateways) must never pick
     * up the load-balancer interceptor, which would try to resolve that host
     * as a service id and fail with "No instances available for <host>".
     * (Originally added for the Eureka client's own registry transport, which
     * hit exactly that; Eureka is retired, the hazard for external clients is
     * not.) Prototype-scoped to mirror Spring Boot's auto-configured builder.
     */
    @Bean
    @Primary
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(OutboundHttp outboundHttp) {
        return RestClient.builder().requestFactory(outboundHttp.requestFactory());
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(OutboundHttp outboundHttp) {
        return RestClient.builder().requestFactory(outboundHttp.requestFactory());
    }
}
