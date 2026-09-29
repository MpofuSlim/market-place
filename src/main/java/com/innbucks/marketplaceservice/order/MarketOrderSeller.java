package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * How one seller's share of one order reaches the buyer (V20
 * {@code market_order_seller}), written at order creation for EVERY seller in
 * the basket and never changed after.
 *
 * <p>A record, not an exception list: the order view needs each seller's
 * method before payment, when there are no parcels yet, and neither sibling
 * snapshot covers every seller (the fee rows only exist for a delivering
 * seller, the collection-point rows only for a collecting seller who has a
 * point). When the order is paid, each parcel copies its seller's method.
 */
@Entity
@Table(name = "market_order_seller")
@IdClass(MarketOrderSeller.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketOrderSeller {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Id
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 16)
    private DeliveryMethod deliveryMethod;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID orderId;
        private UUID merchantId;
    }
}
