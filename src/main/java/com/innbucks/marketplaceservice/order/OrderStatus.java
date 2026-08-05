package com.innbucks.marketplaceservice.order;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Order lifecycle. {@code PENDING_PAYMENT} is the only non-terminal state —
 * it holds reserved stock until the platform payments service confirms
 * ({@code PAID}), the buyer cancels ({@code CANCELLED}), or the payment TTL
 * lapses and the expiry sweep releases the stock ({@code EXPIRED}). Legal
 * moves live in {@link OrderStateMachine}; terminals are immutable.
 */
@Schema(description = "Order lifecycle status")
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    CANCELLED,
    EXPIRED
}
