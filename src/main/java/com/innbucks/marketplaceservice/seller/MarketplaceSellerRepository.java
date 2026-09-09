package com.innbucks.marketplaceservice.seller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MarketplaceSellerRepository extends JpaRepository<MarketplaceSeller, UUID> {

    /** The admin queue: one status, oldest first — served by
     *  {@code idx_seller_status_created}. */
    Page<MarketplaceSeller> findByStatusOrderByCreatedAtAsc(SellerStatus status, Pageable pageable);

    Page<MarketplaceSeller> findAllByOrderByCreatedAtAsc(Pageable pageable);
}
