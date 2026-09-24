package com.innbucks.marketplaceservice.pickup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One place a seller hands goods over (V18 {@code seller_collection_point}).
 *
 * <p>Belongs to the SELLER, not to a listing: every listing a seller has is
 * collectable at every one of their points, because a seller's counter sells
 * everything the seller sells. Hard-deleted when removed; orders never read
 * through it (they snapshot it), so removing one cannot move a collection a
 * buyer was already told about.
 */
@Entity
@Table(name = "seller_collection_point")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionPoint {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** The selling organization's id — the same value as a listing's merchantId. */
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** A code from {@code delivery_town}; rendered through DeliveryTownCatalog. */
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

    /** E.164; may be a landline, since it is a counter's number. */
    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "hours_note", length = 160)
    private String hoursNote;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** Exactly one per seller whenever the seller has any point: what a buyer
     *  who does not choose collects from. Changed only by the service's ordered
     *  bulk statements (demote THEN mark), never by flipping this field. */
    @Column(name = "is_default", nullable = false, insertable = false, updatable = false)
    private boolean defaultPoint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
