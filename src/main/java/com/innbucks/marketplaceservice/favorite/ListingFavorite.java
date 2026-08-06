package com.innbucks.marketplaceservice.favorite;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A buyer's favorite (V6 {@code listing_favorite} table). Inserted ONLY via
 * the repository's native {@code ON CONFLICT DO NOTHING} — a repeat add is a
 * DB-level no-op, so the API's idempotency never depends on a read-then-write
 * race. Deliberately NO audit rows (high-volume, low-sensitivity — see
 * CLAUDE.md); the entity exists mainly so JPQL can join it for the
 * newest-favorited-first listing page.
 */
@Entity
@Table(name = "listing_favorite")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListingFavorite {

    @EmbeddedId
    private FavoriteId id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID buyerUuid() {
        return id.getBuyerUuid();
    }

    public UUID listingId() {
        return id.getListingId();
    }
}
