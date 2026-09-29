package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;

import java.util.List;
import java.util.UUID;

/**
 * One seller's share of a priced checkout basket (V20): how their goods would
 * reach the buyer, what that costs, and which methods they could reach the
 * buyer by at all.
 *
 * <p>{@code availableMethods} is computed here, by the pricer, from the same
 * coverage and seller rows it prices with - so the app can grey out Deliver or
 * Collect for a seller before the shopper chooses, without copying the rules.
 * For the lines the pricer would otherwise accept and the methods the cell
 * offers, a method is listed exactly when pricing this seller with it raises no
 * delivery-method issue.
 *
 * @param method            the method this checkout priced the seller with
 * @param deliveryFeeCents  the seller's fee to the town when they deliver and
 *                          at least one of their lines is delivered there; null
 *                          otherwise
 * @param collectionOffered false for a delivery-only seller
 * @param availableMethods  the cell's methods this seller can be reached by, in
 *                          the cell's order
 */
public record SellerPricing(UUID merchantId,
                            DeliveryMethod method,
                            Long deliveryFeeCents,
                            boolean collectionOffered,
                            List<DeliveryMethod> availableMethods) {

    public SellerPricing {
        availableMethods = availableMethods == null ? List.of() : List.copyOf(availableMethods);
    }
}
