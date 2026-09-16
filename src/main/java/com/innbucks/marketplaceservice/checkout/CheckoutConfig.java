package com.innbucks.marketplaceservice.checkout;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link CheckoutProperties} — the per-cell delivery and payment-rail
 * settings that the cart, the checkout quote and order creation all read.
 * Explicit registration rather than a component scan, matching
 * {@code NotificationClientConfig}'s stance in this repo.
 */
@Configuration
@EnableConfigurationProperties(CheckoutProperties.class)
public class CheckoutConfig {
}
