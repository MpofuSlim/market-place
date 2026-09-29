package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.checkout.CheckoutProperties;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two cell-level facts that decide whether a seller may turn collection
 * OFF and become delivery-only (V20).
 *
 * <ul>
 *   <li>{@code marketplace.delivery.delivery-only-sellers-enabled} — the
 *       rollout switch, OFF by default like {@code variants-enabled}. It gates
 *       only the MOVE to delivery-only: turning collection back on is never
 *       gated, and a seller already delivery-only stays so when the switch is
 *       turned off (an operator can switch them back).</li>
 *   <li>Whether this cell offers DELIVERY at all
 *       ({@code marketplace.delivery.methods}). A delivery-only seller in a
 *       market with no delivery would sell nothing, so the move is refused
 *       there rather than leaving every one of their items unbuyable.</li>
 * </ul>
 *
 * <p>Read-only over {@link CheckoutProperties}: checkout owns what the cell
 * offers, this only asks.
 */
@Component
@Slf4j
public class DeliveryOnlyPolicy {

    private final boolean enabled;
    private final CheckoutProperties checkout;

    public DeliveryOnlyPolicy(
            @Value("${marketplace.delivery.delivery-only-sellers-enabled:false}") boolean enabled,
            CheckoutProperties checkout) {
        this.enabled = enabled;
        this.checkout = checkout;
        if (enabled) {
            // A WARN, not an ERROR: both states are legitimate. Until a basket
            // can choose delivery or collection per seller, a basket holding a
            // delivery-only seller's item next to an item that can only be
            // collected has no single method that works for both, so the app
            // has to ask the shopper to split it into two orders.
            log.warn("Delivery-only sellers are ENABLED on this cell, and a basket cannot yet "
                    + "choose delivery or collection per seller: a basket mixing a delivery-only "
                    + "seller with an item that can only be collected cannot be checked out as "
                    + "one order");
        }
    }

    /** Whether a seller may turn collection off on this cell. */
    public boolean enabled() {
        return enabled;
    }

    /** Whether this cell offers delivery at all — a delivery-only seller
     *  needs it. */
    public boolean deliveryOffered() {
        return checkout.getDelivery().getMethods().contains(DeliveryMethod.DELIVERY);
    }
}
