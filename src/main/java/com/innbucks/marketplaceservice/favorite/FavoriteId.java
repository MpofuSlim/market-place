package com.innbucks.marketplaceservice.favorite;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@code listing_favorite}: (buyer, listing) IS the row —
 *  the DB-level idempotency of the favorite toggle. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class FavoriteId implements Serializable {

    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;
}
