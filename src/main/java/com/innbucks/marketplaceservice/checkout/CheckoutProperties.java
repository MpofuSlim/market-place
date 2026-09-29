package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Per-cell checkout settings ({@code marketplace.checkout.*} and
 * {@code marketplace.delivery.*}): which payment rails this cell can actually
 * collect on, how the buyer may receive goods, and what delivery costs.
 */
@Data
@ConfigurationProperties(prefix = "marketplace")
public class CheckoutProperties {

    private final Checkout checkout = new Checkout();
    private final Delivery delivery = new Delivery();

    @Data
    public static class Checkout {
        /**
         * The payment rails {@code GET /marketplace/checkout/payment-methods}
         * advertises, in the order the app should offer them.
         *
         * <p><b>This MUST mirror what payment-service is actually provisioned
         * with on this cell.</b> Marketplace-service holds no payment
         * credentials and cannot ask — the rails live behind
         * {@code POST /payments}, whose ZimSwitch and EcoCash legs each refuse
         * with a 503 when their credentials are blank. Advertising a rail the
         * cell cannot collect on sends the buyer down a path that dead-ends at
         * someone else's error.
         *
         * <p>Hence the default is the InnBucks code rail ALONE: it is the
         * historical default rail every cell has, so the fail-safe answer is
         * "only what we know is there". A cell adds the others when it
         * provisions them (see the ZimSwitch half-provisioned lesson in the
         * ticketing CLAUDE.md — a rail that looks live and 503s at the gateway
         * is the failure mode this default exists to avoid).
         */
        private List<PaymentRail> paymentMethods = List.of(PaymentRail.INNBUCKS_CODE);
    }

    @Data
    public static class Delivery {
        /**
         * How goods may reach the buyer on this cell. Both by default; a cell
         * serving a market with no courier arrangement can drop DELIVERY and
         * the checkout stops offering it.
         */
        private Set<DeliveryMethod> methods = EnumSet.allOf(DeliveryMethod.class);

        /**
         * V20: whether a basket may choose delivery or collection per SELLER
         * ({@code sellerDeliveryMethods} on the quote and the order). OFF by
         * default, like {@code variants-enabled}: it gates only a NON-EMPTY
         * choice list (422 {@code seller_delivery_methods_disabled}), so a body
         * without one behaves exactly as before, and switching it off never
         * touches an order already placed with one.
         *
         * <p><b>Rollout:</b> switch it on only after the V21 image is fully
         * rolled out, as its own step. A pod on the previous image ignores the
         * choices and reads the order's method for every parcel, which is
         * wrong for a mixed order; once one exists, roll forward only (the
         * rollback check is in V21's header).
         */
        private boolean perSellerMethodsEnabled = false;

        /** Abuse guard on the address book. A real shopper has a handful of
         *  destinations; a thousand is someone using it as free storage. */
        private int maxAddressesPerBuyer = 25;
    }
}
