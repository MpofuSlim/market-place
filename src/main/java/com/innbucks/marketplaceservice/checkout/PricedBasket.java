package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A whole basket priced against the live catalogue: every line in REQUEST
 * order (so a client can render its own list unchanged), the subtotal of the
 * lines that can actually be bought, and the issues that would refuse an
 * order built from it.
 *
 * <p>{@code subtotalCents} counts SELLABLE lines only. A cart with a sold-out
 * item shows a subtotal for what is still buyable rather than a figure the
 * shopper can never be charged.
 *
 * <p>{@code sellers} (V20) is each on-sale seller's share of a CHECKOUT pricing
 * - the method they were priced with, their fee, whether they collect and which
 * methods could reach the buyer - in basket order. Empty for the cart, which
 * asks no delivery question.
 */
public record PricedBasket(List<PricedLine> lines,
                           long subtotalCents,
                           List<OrderLineRejection> issues,
                           long deliveryFeeCents,
                           Map<UUID, Long> deliveryFeesByMerchant,
                           Map<UUID, SellerPricing> sellers) {

    /** A basket priced with no delivery (the cart). */
    public PricedBasket(List<PricedLine> lines, long subtotalCents, List<OrderLineRejection> issues) {
        this(lines, subtotalCents, issues, 0L, Map.of(), Map.of());
    }

    /** True when an order built from this basket would be accepted. */
    public boolean checkoutReady() {
        return issues.isEmpty() && !lines.isEmpty();
    }

    /** One seller's share, or null for a seller with no on-sale line (and always on the cart). */
    public SellerPricing seller(UUID merchantId) {
        return sellers.get(merchantId);
    }
}
