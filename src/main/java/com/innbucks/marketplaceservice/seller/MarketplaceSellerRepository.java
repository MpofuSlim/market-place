package com.innbucks.marketplaceservice.seller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
