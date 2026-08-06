package com.innbucks.marketplaceservice.catalog;

import java.util.UUID;

/**
 * In-process domain event: a listing's {@code stock_qty} moved from 0 to
 * &gt;0 — either a merchant stock update or an order cancel/expiry returning
 * the last reserved units. Published INSIDE the transaction that restocked, so
 * the {@code AFTER_COMMIT} listener (restock-alert foundation,
 * {@code favorite/RestockAlertListener}) never fires for a rolled-back
 * restock.
 *
 * <p>This is the FOUNDATION for back-in-stock notifications only: the listener
 * counts favoriters, logs, and increments {@code marketplace.restock_events}.
 * Wiring it to the fleet SMS/notification gateway is the deliberate next step
 * (notifications are deferred in this repo — see CLAUDE.md); do NOT add an
 * external HTTP client from the listener without the fleet WireMock contract
 * test convention.
 */
public record ListingRestocked(UUID listingId) {
}
