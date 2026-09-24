package com.innbucks.marketplaceservice.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * One seller's delivery fee on a DELIVERY order (V14
 * {@code market_order_delivery_fee}), fixed at ORDER time.
 *
 * <p>Per seller because each seller ships their own parcel; fixed at order time
 * because a seller can change a town's fee between the order and its payment,
 * and the buyer pays what they were quoted. When the order is paid, each
 * parcel copies its seller's fee and the settlement holds it with the goods.
 */
@Entity
@Table(name = "market_order_delivery_fee")
@IdClass(MarketOrderDeliveryFee.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketOrderDeliveryFee {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Id
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "fee_cents", nullable = false)
    private long feeCents;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID orderId;
        private UUID merchantId;
    }
}
