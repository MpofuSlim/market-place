package com.innbucks.marketplaceservice.seller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
