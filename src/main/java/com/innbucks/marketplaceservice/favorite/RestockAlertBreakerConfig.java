package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.notify.FanoutCircuitBreaker;
import com.innbucks.marketplaceservice.notify.MarketplaceNotificationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The restock fan-out's circuit breaker ({@link RestockAlertListener}). One
 * instance per process, shared by both bulk-pool threads, so a failure seen
 * by one restock event protects the next one too.
 */
@Configuration
public class RestockAlertBreakerConfig {

    /** Breaker name; the {@code breaker} tag on its metrics. */
    public static final String BREAKER_NAME = "restock_alert_user_notify";

    @Bean
    public FanoutCircuitBreaker restockAlertBreaker(MarketplaceNotificationProperties properties,
                                                    MeterRegistry registry) {
        return new FanoutCircuitBreaker(BREAKER_NAME, properties.getRestockAlerts().getBreaker(),
                Clock.systemUTC(), registry);
    }
}
