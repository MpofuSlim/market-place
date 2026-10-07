package com.innbucks.marketplaceservice.catalog;

import java.util.UUID;

/**
 * In-process domain event: a listing's {@code stock_qty} moved from 0 to
 * &gt;0 — either a merchant stock update or an order cancel/expiry returning
 * the last reserved units. Published INSIDE the transaction that restocked, so
 * the {@code AFTER_COMMIT} listener ({@code favorite/RestockAlertListener})
 * never fires for a rolled-back restock. That listener alerts each favoriter
 * through user-service, on the bulk notification pool, behind a circuit
 * breaker ({@code notify/FanoutCircuitBreaker}) — see CLAUDE.md "Restock events".
 */
public record ListingRestocked(UUID listingId) {
}
