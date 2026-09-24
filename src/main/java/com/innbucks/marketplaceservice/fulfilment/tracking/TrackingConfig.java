package com.innbucks.marketplaceservice.fulfilment.tracking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link TrackingProperties} — explicit registration, this repo's stance. */
@Configuration
@EnableConfigurationProperties(TrackingProperties.class)
public class TrackingConfig {
}
