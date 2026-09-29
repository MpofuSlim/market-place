package com.innbucks.marketplaceservice.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MarketOrderSellerRepository
        extends JpaRepository<MarketOrderSeller, MarketOrderSeller.Key> {

    List<MarketOrderSeller> findByOrderId(UUID orderId);

    /** A whole page of orders in one query. */
    List<MarketOrderSeller> findByOrderIdIn(Collection<UUID> orderIds);
}
