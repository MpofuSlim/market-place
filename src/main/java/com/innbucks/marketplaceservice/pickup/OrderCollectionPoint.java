package com.innbucks.marketplaceservice.pickup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where one seller's goods on a COLLECTION order are collected (V18
 * {@code market_order_collection_point}): a SNAPSHOT taken at order time.
 *
 * <p>The fields are copied, and {@code collectionPointId} is provenance only.
 * A seller renaming, moving or deleting the point must not silently move goods
 * a buyer was already told to fetch from somewhere else — the same discipline
 * as the order's {@code delivery_*} columns. Opening hours are deliberately NOT
 * copied: views read them live through the provenance id, because "when can I
 * come" is a question about today.
 */
@Entity
@Table(name = "market_order_collection_point")
@IdClass(OrderCollectionPoint.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCollectionPoint {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Id
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "collection_point_id", nullable = false)
    private UUID collectionPointId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "town_code", nullable = false, length = 40)
    private String townCode;

    @Column(name = "line1", nullable = false, length = 160)
    private String line1;

    @Column(name = "line2", length = 160)
    private String line2;

    @Column(name = "area", length = 80)
    private String area;

    @Column(name = "landmark", length = 160)
    private String landmark;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** Copies a live point into an order's snapshot. */
    public static OrderCollectionPoint of(UUID orderId, CollectionPoint point) {
        return OrderCollectionPoint.builder()
                .orderId(orderId)
                .merchantId(point.getMerchantId())
                .collectionPointId(point.getId())
                .name(point.getName())
                .townCode(point.getTownCode())
                .line1(point.getLine1())
                .line2(point.getLine2())
                .area(point.getArea())
                .landmark(point.getLandmark())
                .phone(point.getPhone())
                .latitude(point.getLatitude())
                .longitude(point.getLongitude())
                .build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID orderId;
        private UUID merchantId;
    }
}
