package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;

import java.util.List;

/**
 * A whole basket priced against the live catalogue: every line in REQUEST
 * order (so a client can render its own list unchanged), the subtotal of the
 * lines that can actually be bought, and the issues that would refuse an
 * order built from it.
 *
 * <p>{@code subtotalCents} counts SELLABLE lines only. A cart with a sold-out
 * item shows a subtotal for what is still buyable rather than a figure the
 * shopper can never be charged.
 */
public record PricedBasket(List<PricedLine> lines,
                           long subtotalCents,
                           List<OrderLineRejection> issues) {

    /** True when an order built from this basket would be accepted. */
    public boolean checkoutReady() {
        return issues.isEmpty() && !lines.isEmpty();
    }
}
