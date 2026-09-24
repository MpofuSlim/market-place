package com.innbucks.marketplaceservice.pickup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionPointRepository extends JpaRepository<CollectionPoint, UUID> {

    /** The seller's points, default first, then oldest first — the order every
     *  surface lists them in, so "the first one" means the same everywhere. */
    @Query("""
            SELECT p FROM CollectionPoint p
             WHERE p.merchantId = :merchantId
             ORDER BY p.defaultPoint DESC, p.createdAt ASC, p.id ASC""")
    List<CollectionPoint> findForMerchant(@Param("merchantId") UUID merchantId);

    /** Batch: every point of every seller on a page, one query. */
    @Query("""
            SELECT p FROM CollectionPoint p
             WHERE p.merchantId IN :merchantIds
             ORDER BY p.merchantId, p.defaultPoint DESC, p.createdAt ASC, p.id ASC""")
    List<CollectionPoint> findForMerchants(@Param("merchantIds") Collection<UUID> merchantIds);

    /** Owner-scoped lookup: another seller's point and a missing one are the
     *  same empty result, so the API is no existence oracle. */
    Optional<CollectionPoint> findByIdAndMerchantId(UUID id, UUID merchantId);

    long countByMerchantId(UUID merchantId);

    /**
     * The first half of making a point the default: clear the flag on every
     * point of the seller. Run BEFORE {@link #markDefault}, so the partial
     * unique index never sees two defaults inside the transaction (the
     * gallery's primary-image discipline).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seller_collection_point SET is_default = FALSE, updated_at = :now
             WHERE merchant_id = :merchantId AND is_default""", nativeQuery = true)
    int demoteDefaults(@Param("merchantId") UUID merchantId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seller_collection_point SET is_default = TRUE, updated_at = :now
             WHERE id = :id AND merchant_id = :merchantId""", nativeQuery = true)
    int markDefault(@Param("id") UUID id, @Param("merchantId") UUID merchantId,
                    @Param("now") Instant now);
}
