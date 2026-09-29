package com.innbucks.marketplaceservice.checkout;

/**
 * A checkout's basket, loaded, and the delivery plan resolved against the
 * sellers actually on sale in it - what {@link CheckoutService#plan} hands the
 * quote and order creation alike, before an address is resolved or anything is
 * priced (V20: load, then plan, then price).
 */
public record PlannedCheckout(LoadedBasket loaded, DeliveryPlan plan) {
}
