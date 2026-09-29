package com.innbucks.marketplaceservice.seller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface MarketplaceSellerRepository extends JpaRepository<MarketplaceSeller, UUID> {

    /** The admin queue: one status, oldest first — served by
     *  {@code idx_seller_status_created}. */
    Page<MarketplaceSeller> findByStatusOrderByCreatedAtAsc(SellerStatus status, Pageable pageable);

    Page<MarketplaceSeller> findAllByOrderByCreatedAtAsc(Pageable pageable);

    /**
     * Takes the seller's row lock for the rest of the caller's transaction.
     * Writes that keep a per-seller invariant the database can only half-check
     * (exactly one default collection point: the partial unique index stops
     * two, only the service can stop zero) serialise on it, so two concurrent
     * first points cannot both become the default and 500 on the index.
     */
    @Query(value = "SELECT merchant_id FROM marketplace_seller WHERE merchant_id = :merchantId FOR UPDATE",
            nativeQuery = true)
    UUID lockForUpdate(@Param("merchantId") UUID merchantId);

    /**
     * Creates the seller's PENDING trust record unless it already exists — the
     * race-safe half of "ensure, then lock". A find-then-save lets two
     * concurrent first writes both miss the row and both insert, and the loser
     * 500s on the primary key; here the loser's insert waits for the winner and
     * then does nothing.
     *
     * @return 1 when this call created the row, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO marketplace_seller (merchant_id, status, created_at)
            VALUES (:merchantId, 'PENDING', :now)
            ON CONFLICT (merchant_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("merchantId") UUID merchantId, @Param("now") Instant now);

    /**
     * The ONLY writer of {@code collection_enabled} (V20) and its stamps, which
     * are read-only on the entity. A bulk statement rather than an entity save
     * because the entity has no {@code @Version}: a save from any other path
     * would put back whatever value that path loaded.
     *
     * @return rows updated; 0 when the seller has no record
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE marketplace_seller
               SET collection_enabled = :enabled,
                   collection_updated_at = :now,
                   collection_updated_by = :by
             WHERE merchant_id = :merchantId""", nativeQuery = true)
    int setCollectionEnabled(@Param("merchantId") UUID merchantId,
                             @Param("enabled") boolean enabled,
                             @Param("now") Instant now,
                             @Param("by") UUID by);

    /** A seller's collection setting, read as a projection. */
    interface CollectionState {
        Boolean getCollectionEnabled();

        Instant getCollectionUpdatedAt();
    }

    /**
     * The seller's collection setting as the DATABASE holds it, never the
     * persistence context's copy — for the re-read under the seller lock,
     * where an entity loaded before the lock was granted would be stale (a
     * repeated {@code findById} hands back that same stale instance). Null
     * when the seller has no record.
     */
    @Query("SELECT s.collectionEnabled AS collectionEnabled, "
            + "s.collectionUpdatedAt AS collectionUpdatedAt "
            + "FROM MarketplaceSeller s WHERE s.merchantId = :merchantId")
    CollectionState collectionStateOf(@Param("merchantId") UUID merchantId);

    /**
     * Of the given sellers, the ones that do NOT collect. Selecting only the
     * delivery-only ones means a seller with no record reads as collecting,
     * which is what every seller was before V20. One query for a whole basket.
     */
    @Query("SELECT s.merchantId FROM MarketplaceSeller s "
            + "WHERE s.merchantId IN :merchantIds AND s.collectionEnabled = false")
    Set<UUID> findCollectionDisabledAmong(@Param("merchantIds") Collection<UUID> merchantIds);
}
