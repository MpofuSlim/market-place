package com.innbucks.marketplaceservice.catalog.variant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Variant rows (V19). Every stock statement here is native and is run ONLY by
 * {@code catalog.ListingStock}, under the LOCK RULE:
 *
 * <ol>
 *   <li>A variant row is locked or modified ONLY while the transaction already
 *       holds its parent listing's row lock.</li>
 *   <li>Across listings, in {@code java.util.UUID.compareTo} order, one
 *       statement at a time — never an SQL {@code ORDER BY ... FOR UPDATE}:
 *       Java compares UUIDs signed and Postgres unsigned, and mixing the two
 *       orders is how lock cycles form.</li>
 *   <li>Explicit locks are {@code FOR NO KEY UPDATE}, never {@code FOR
 *       UPDATE}, so FK inserts into child tables are never blocked.</li>
 * </ol>
 *
 * Every movement ends with {@code ListingRepository.recomputeStockTotal} in
 * the same transaction. The guarded {@link #reserve} is the ONLY oversell
 * guard for a variant line.
 */
public interface ListingVariantRepository extends JpaRepository<ListingVariant, UUID> {

    /** Reserves {@code q} units of one option; 0 = not enough, or not an
     *  option of that listing. */
    @Modifying
    @Query(value = """
            UPDATE listing_variant SET stock_qty = stock_qty - :q
             WHERE id = :vid AND listing_id = :lid AND stock_qty >= :q
            """, nativeQuery = true)
    int reserve(@Param("vid") UUID variantId, @Param("lid") UUID listingId, @Param("q") int q);

    /** Returns {@code q} units to one option; 0 = the option was removed. */
    @Modifying
    @Query(value = """
            UPDATE listing_variant SET stock_qty = stock_qty + :q
             WHERE id = :vid AND listing_id = :lid
            """, nativeQuery = true)
    int restock(@Param("vid") UUID variantId, @Param("lid") UUID listingId, @Param("q") int q);

    /** The seller's absolute set on one option; 0 = not an option of that
     *  listing. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE listing_variant SET stock_qty = :qty, updated_at = :now
             WHERE id = :vid AND listing_id = :lid
            """, nativeQuery = true)
    int setStock(@Param("vid") UUID variantId, @Param("lid") UUID listingId,
                 @Param("qty") int qty, @Param("now") Instant now);

    boolean existsByIdAndListingId(UUID id, UUID listingId);

    List<ListingVariant> findByListingIdOrderByPositionAsc(UUID listingId);

    /** A page's variants in ONE query, grouped by listing in position order. */
    List<ListingVariant> findByListingIdInOrderByListingIdAscPositionAsc(Collection<UUID> listingIds);
}
