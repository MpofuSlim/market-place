package com.innbucks.marketplaceservice.catalog;

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
 * One town a listing delivers to, with the fee the seller charges to get it
 * there (V14 {@code listing_delivery_town}). A listing with no rows is
 * collection-only; there is deliberately no separate "deliverable" flag to
 * drift out of step with this table.
 */
@Entity
@Table(name = "listing_delivery_town")
@IdClass(ListingDeliveryTown.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListingDeliveryTown {

    @Id
    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Id
    @Column(name = "town_code", nullable = false, length = 40)
    private String townCode;

    /** Minor units; zero is a legitimate "free delivery here". */
    @Column(name = "fee_cents", nullable = false)
    private long feeCents;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID listingId;
        private String townCode;
    }
}
