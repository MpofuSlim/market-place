package com.innbucks.marketplaceservice.pickup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderCollectionPointRepository
        extends JpaRepository<OrderCollectionPoint, OrderCollectionPoint.Key> {

    List<OrderCollectionPoint> findByOrderId(UUID orderId);

    /** Batch: every snapshot for a page of orders, one query. */
    List<OrderCollectionPoint> findByOrderIdIn(Collection<UUID> orderIds);
}
