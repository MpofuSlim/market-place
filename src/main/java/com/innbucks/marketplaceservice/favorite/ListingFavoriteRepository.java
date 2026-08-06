package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.catalog.Listing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ListingFavoriteRepository extends JpaRepository<ListingFavorite, FavoriteId> {

    /**
     * Idempotent add: the composite PK plus {@code ON CONFLICT DO NOTHING}
     * makes a repeat (or concurrent duplicate) add a 0-count no-op — never an
     * error, never a moved {@code created_at} (the original favorited-order is
     * preserved). Native SQL because JPQL has no upsert.
     */
    @Modifying
    @Query(value = """
            INSERT INTO listing_favorite (buyer_uuid, listing_id, created_at)
            VALUES (:buyerUuid, :listingId, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("buyerUuid") UUID buyerUuid,
                       @Param("listingId") UUID listingId,
                       @Param("createdAt") Instant createdAt);

    /** Idempotent remove — 0 rows deleted is a legal outcome, not an error. */
    @Modifying
    @Query("delete from ListingFavorite f where f.id.buyerUuid = :buyerUuid and f.id.listingId = :listingId")
    int remove(@Param("buyerUuid") UUID buyerUuid, @Param("listingId") UUID listingId);

    /**
     * The caller's favorited LISTINGS, newest-favorited first (ordered by the
     * favorite row's created_at, NOT the listing's). Callers must pass an
     * UNSORTED pageable — the ordering lives in the query. Any listing status
     * comes back; the response carries it so the FE can render "no longer
     * available".
     */
    @Query(value = """
            select l
              from Listing l, ListingFavorite f
             where f.id.listingId = l.id
               and f.id.buyerUuid = :buyerUuid
             order by f.createdAt desc
            """,
            countQuery = "select count(f) from ListingFavorite f where f.id.buyerUuid = :buyerUuid")
    Page<Listing> findFavoriteListings(@Param("buyerUuid") UUID buyerUuid, Pageable pageable);

    /** Favoriter count for the restock-alert listener (drives the overflow
     *  metric against the recipient cap). Deliberately NOT exposed on any
     *  public surface. */
    long countByIdListingId(UUID listingId);

    /**
     * The buyer uuids to alert when a favorited listing restocks,
     * OLDEST-favorite first — under the per-event recipient cap the buyers who
     * waited longest win the slots. Callers pass an unsorted pageable sized to
     * the cap; the ordering lives in the query.
     */
    @Query("select f.id.buyerUuid from ListingFavorite f "
            + "where f.id.listingId = :listingId order by f.createdAt asc")
    List<UUID> findFavoriterUuids(@Param("listingId") UUID listingId, Pageable pageable);
}
