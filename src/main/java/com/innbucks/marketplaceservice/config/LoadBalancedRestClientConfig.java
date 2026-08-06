package com.innbucks.marketplaceservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.Builder} that resolves {@code http://<service-name>} URLs
 * through the Eureka registry via Spring Cloud LoadBalancer (fleet copy —
 * InnRewards). {@code UserNotifyGateway} clones this builder so its
 * {@code http://user-service} calls are discovery-routed instead of hitting a
 * hardcoded host:port.
 */
@Configuration
public class LoadBalancedRestClientConfig {

    /**
     * The plain (non-load-balanced) builder, kept @Primary so anything that
     * autowires a RestClient.Builder by type gets this one — crucially the
     * Eureka client's own RestClient transport, which talks to the registry at
     * a fixed URL. If Eureka picked up the @LoadBalanced builder it would try to
     * resolve the registry host ("localhost") as a service id, which fails with
     * BeanCurrentlyInCreationException + "No instances available for localhost".
     * Prototype-scoped to mirror Spring Boot's auto-configured builder.
     */
    @Bean
    @Primary
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
