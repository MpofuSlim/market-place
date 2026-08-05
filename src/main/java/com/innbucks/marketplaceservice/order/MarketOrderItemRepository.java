package com.innbucks.marketplaceservice.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MarketOrderItemRepository extends JpaRepository<MarketOrderItem, UUID> {

    List<MarketOrderItem> findByOrderId(UUID orderId);

    /** Batch load for the paged my-orders view — one query per page, not one
     *  per order. */
    List<MarketOrderItem> findByOrderIdIn(Collection<UUID> orderIds);
}
